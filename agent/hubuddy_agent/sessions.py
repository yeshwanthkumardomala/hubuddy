"""Paired phones: opaque 32-byte tokens, only their SHA-256 stored, long-lived, capped in number."""
import hashlib
import json
import pathlib
import re
import secrets
import time

from .privatestore import write_private_json

TTL_MS = 180 * 24 * 3600 * 1000
MAX_DEVICES = 32
TOKEN_SHAPE = re.compile(r"^[A-Za-z0-9_-]{43}$")


class StoreError(RuntimeError):
    pass


def _digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class SessionStore:
    def __init__(self, path: pathlib.Path, now=lambda: int(time.time() * 1000), ttl_ms: int = TTL_MS, cap: int = MAX_DEVICES):
        self.path, self.now, self.ttl_ms, self.cap = pathlib.Path(path), now, ttl_ms, cap
        self.entries: list[dict] = []
        self._last_save = 0
        try:
            saved = json.loads(self.path.read_text(encoding="utf-8"))
            if saved.get("version") != 1 or not isinstance(saved.get("entries"), list) or len(saved["entries"]) > cap * 4:
                raise ValueError("shape")
            self.entries = [e for e in saved["entries"] if re.fullmatch(r"[0-9a-f]{64}", str(e.get("hash", ""))) and int(e.get("expires", 0)) > now()]
        except FileNotFoundError:
            pass
        except (OSError, ValueError, TypeError) as e:
            raise StoreError("Saved phone pairings are unreadable. Restore the file, or run with --reset-pairing "
                             "(phones must then be paired again).") from e

    def _save(self):
        write_private_json(self.path, {"version": 1, "entries": self.entries})
        self._last_save = self.now()

    def issue(self, device: str = "phone") -> str:
        now = self.now()
        self.entries = [e for e in self.entries if e["expires"] > now]
        if len(self.entries) >= self.cap:
            raise StoreError("Paired phone limit reached. Forget a phone on this computer first.")
        token = secrets.token_urlsafe(32)
        name = "".join(c for c in str(device) if c.isprintable())[:40].strip() or "phone"
        self.entries.append({"hash": _digest(token), "expires": now + self.ttl_ms, "name": name, "created": now, "last_seen": now})
        self._save()
        return token

    def verify(self, token) -> dict | None:
        """The matching entry, or None. Updates last-seen at most once a minute."""
        if not isinstance(token, str) or not TOKEN_SHAPE.match(token):
            return None
        d, now = _digest(token), self.now()
        hit = None
        for e in self.entries:  # compare against all entries so timing does not reveal which one matched
            if secrets.compare_digest(e["hash"], d) and e["expires"] > now:
                hit = e
        if hit and now - hit.get("last_seen", 0) > 60_000:
            hit["last_seen"] = now
            if now - self._last_save > 60_000:
                self._save()
        return hit

    def session_id(self, entry: dict) -> str:
        return entry["hash"][:12]

    def listing(self) -> list[dict]:
        return [{"id": self.session_id(e), "name": e["name"], "created": e["created"], "last_seen": e.get("last_seen", 0)} for e in self.entries]

    def revoke(self, sid: str) -> bool:
        before = len(self.entries)
        self.entries = [e for e in self.entries if self.session_id(e) != sid]
        if len(self.entries) != before:
            self._save()
        return len(self.entries) != before

    def revoke_all(self) -> None:
        self.entries = []
        self._save()

    def __len__(self) -> int:
        now = self.now()
        return sum(1 for e in self.entries if e["expires"] > now)
