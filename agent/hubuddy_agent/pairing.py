"""Pairing: the QR invitation, and the PIN + compare-code alternative.

QR (strongest): the invitation carries the CA to pin, so trust is established out of band.

PIN (no camera): a fresh 6-digit PIN is shown on this PC for two minutes together with a compare code, a short
fingerprint of this PC's identity. The phone shows the compare code it received over TLS; the user checks that the two
match, and only then sends the PIN. A man-in-the-middle presents a different identity and therefore a different code.
"""
import hashlib
import hmac
import secrets
import time

from .invites import Invites

PIN_LENGTH = 6
PIN_TTL_MS = 2 * 60_000
CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  # Crockford base32: no I, L, O, U


def compare_code(pin_hex: str) -> str:
    """12 characters (60 bits) from the identity fingerprint, as XXXX-XXXX-XXXX. The phone computes the same."""
    bits = int.from_bytes(bytes.fromhex(pin_hex)[:8], "big") >> 4  # top 60 bits of the first 8 bytes
    chars = []
    for _ in range(12):
        chars.append(CODE_ALPHABET[bits & 31])
        bits >>= 5
    s = "".join(reversed(chars))
    return f"{s[:4]}-{s[4:8]}-{s[8:]}"


def new_pin(length: int = PIN_LENGTH) -> str:
    return "".join(str(secrets.randbelow(10)) for _ in range(length))


class PairingManager:
    def __init__(self, now=lambda: int(time.time() * 1000)):
        self.now = now
        self.invites = Invites(now)
        self._pin: tuple[bytes, int] | None = None

    # ---- PIN mode -------------------------------------------------------------------
    def start_pin(self) -> dict:
        pin = new_pin()
        self._pin = (hashlib.sha256(pin.encode()).digest(), self.now() + PIN_TTL_MS)
        return {"pin": pin, "expires": self._pin[1]}

    def consume_pin(self, pin) -> bool:
        if not isinstance(pin, str) or len(pin) != PIN_LENGTH or not pin.isascii() or not pin.isdigit() or self._pin is None:
            return False
        digest, expires = self._pin
        if expires <= self.now() or not hmac.compare_digest(digest, hashlib.sha256(pin.encode()).digest()):
            return False
        self._pin = None
        return True

    @property
    def pin_active(self) -> bool:
        return self._pin is not None and self._pin[1] > self.now()

    def cancel(self) -> None:
        self._pin = None
        self.invites.clear()

    def status(self) -> dict:
        return {"qr": self.invites.active, "pin": self.pin_active}
