"""One-time pairing invitation: a short-lived secret carried by a QR code shown on this PC.

Only the secret's SHA-256 is kept, it works once, and it expires after two minutes. The invitation text is
"hubuddy-pair:" + base64url(JSON) and contains everything the phone needs to trust this PC: its id, name, the CA
certificate to pin, the addresses and port to try, and the secret.
"""
import base64
import hashlib
import hmac
import json
import re
import secrets
import time

import segno

PREFIX = "hubuddy-pair:"
TTL_MS = 2 * 60_000
SECRET_SHAPE = re.compile(r"^[A-Za-z0-9_-]{43}$")


def _hash(secret: str) -> bytes:
    return hashlib.sha256(secret.encode()).digest()


class Invites:
    def __init__(self, now=lambda: int(time.time() * 1000)):
        self.now = now
        self._active: tuple[bytes, int] | None = None

    def clear(self) -> None:
        self._active = None

    def issue(self, details: dict) -> dict:
        secret = secrets.token_urlsafe(32)
        expires = self.now() + TTL_MS
        self._active = (_hash(secret), expires)
        payload = json.dumps({"v": 1, **details, "secret": secret, "expires": expires}, separators=(",", ":")).encode()
        text = PREFIX + base64.urlsafe_b64encode(payload).decode()
        qr = segno.make(text, error="m", micro=False)
        return {"invitation": text, "expires": expires, "svg": qr.svg_inline(scale=5, border=4, dark="#000000", light="#ffffff"),
                "modules": [[bool(c) for c in row] for row in qr.matrix], "quiet": 4}

    def consume(self, secret) -> bool:
        if not isinstance(secret, str) or not SECRET_SHAPE.match(secret) or self._active is None:
            return False
        digest, expires = self._active
        if expires <= self.now() or not hmac.compare_digest(digest, _hash(secret)):
            return False
        self._active = None
        return True

    @property
    def active(self) -> bool:
        return self._active is not None and self._active[1] > self.now()
