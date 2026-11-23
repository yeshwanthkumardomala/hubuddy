"""Device pairing and bearer-token authentication for the agent.

* A 6-digit pairing code is printed on the PC console. It is single-use, expires,
  and is invalidated after a few wrong guesses.
* A successful pairing returns a random 256-bit device token. Only its SHA-256 is
  stored on disk, so the devices file is useless to someone who copies it.
"""
import hashlib
import hmac
import json
import os
import pathlib
import secrets
import threading
import time

DEFAULT_STORE = pathlib.Path.home() / ".hubuddy" / "agent_devices.json"
CODE_ATTEMPTS = 5


def _sha(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class DeviceAuth:
    def __init__(self, path: pathlib.Path = DEFAULT_STORE, clock=time.time):
        self.path = pathlib.Path(path)
        self.clock = clock
        self._lock = threading.Lock()
        self._code: str | None = None
        self._code_expires = 0.0
        self._code_attempts = 0
        self.devices: list[dict] = self._load()

    # ---- storage ---------------------------------------------------------
    def _load(self) -> list[dict]:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return [d for d in data.get("devices", []) if isinstance(d, dict) and "token_sha256" in d]
        except (OSError, ValueError):
            return []

    def _save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"devices": self.devices}, indent=2), encoding="utf-8")
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, self.path)

    # ---- pairing -----------------------------------------------------------
    def new_code(self, minutes: float = 10) -> str:
        with self._lock:
            self._code = f"{secrets.randbelow(1_000_000):06d}"
            self._code_expires = self.clock() + minutes * 60
            self._code_attempts = 0
            return self._code

    @property
    def pairing_open(self) -> bool:
        return self._code is not None and self.clock() < self._code_expires

    def pair(self, code: str, device: str) -> str | None:
        """Exchange a valid code for a new device token (None if refused)."""
        with self._lock:
            if self._code is None or self.clock() >= self._code_expires:
                self._code = None
                return None
            self._code_attempts += 1
            ok = hmac.compare_digest(str(code).encode(), self._code.encode())
            if not ok:
                if self._code_attempts >= CODE_ATTEMPTS:
                    self._code = None  # too many guesses: the PC must issue a new code
                return None
            self._code = None  # single use
            token = secrets.token_urlsafe(32)
            name = "".join(ch for ch in str(device)[:40] if ch.isprintable()) or "device"
            self.devices.append({"id": secrets.token_hex(4), "name": name, "token_sha256": _sha(token),
                                 "created": int(self.clock()), "last_seen": 0})
            self._save()
            return token

    # ---- verification ------------------------------------------------------
    def check(self, token: str | None) -> dict | None:
        if not token:
            return None
        digest = _sha(token)
        found = None
        for d in self.devices:
            if hmac.compare_digest(d["token_sha256"], digest):
                found = d
        if found is not None:
            now = int(self.clock())
            if now - int(found.get("last_seen", 0)) > 300:
                found["last_seen"] = now
                try:
                    self._save()
                except OSError:
                    pass
        return found

    def revoke(self, device_id: str) -> int:
        before = len(self.devices)
        self.devices = [] if device_id == "all" else [d for d in self.devices if d["id"] != device_id]
        self._save()
        return before - len(self.devices)
