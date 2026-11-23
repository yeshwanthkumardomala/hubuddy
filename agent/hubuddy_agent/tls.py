"""A private certificate authority per installation, so the phone can pin this PC's identity.

* The CA (ECDSA P-256, 10 years) is created once and never replaced; only its own certificate is re-issued, with
  the same key, when it nears expiry.
* The server certificate (90 days) carries a stable signed name, host-<id>.hubuddy.invalid, and is renewed with
  a new key long before it expires.
* A marker file records the CA's fingerprint, so a replaced or damaged identity is detected instead of silently
  accepted (paired phones would otherwise have to be re-paired without warning).

The phone receives the CA certificate inside the one-time pairing invitation (a QR code shown on this PC), so
trust is established out of band and cannot be intercepted on the network.
"""
import base64
import datetime as dt
import hashlib
import json
import pathlib
import ssl
import threading

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

def _utc(d: dt.datetime) -> dt.datetime:
    return d if d.tzinfo else d.replace(tzinfo=dt.timezone.utc)


def not_after(c: x509.Certificate) -> dt.datetime:
    """Expiry as an aware UTC time. cryptography before 42 (Debian 12 ships 38) has only the naive `not_valid_after`."""
    return c.not_valid_after_utc if hasattr(c, "not_valid_after_utc") else _utc(c.not_valid_after)


def not_before(c: x509.Certificate) -> dt.datetime:
    return c.not_valid_before_utc if hasattr(c, "not_valid_before_utc") else _utc(c.not_valid_before)


def issued_by(leaf: x509.Certificate, ca: x509.Certificate) -> None:
    """Raises when [leaf] was not signed by [ca]'s key (the check `verify_directly_issued_by` does from cryptography 40)."""
    if hasattr(leaf, "verify_directly_issued_by"):
        leaf.verify_directly_issued_by(ca)
        return
    if leaf.issuer != ca.subject:
        raise ValueError("issuer does not match")
    ca.public_key().verify(leaf.signature, leaf.tbs_certificate_bytes, ec.ECDSA(leaf.signature_hash_algorithm))


from .privatestore import private_dir, write_private, write_private_json

DAY = dt.timedelta(days=1)
LEAF_DAYS, CA_YEARS = 90, 10
RENEW_LEAF_BEFORE = 30 * DAY
RENEW_CA_BEFORE = 180 * DAY


class IdentityError(RuntimeError):
    """The saved identity is missing, damaged or does not match; the message says how to recover."""


def tls_name(host_id: str) -> str:
    return f"host-{host_id}.hubuddy.invalid"


def _pem_cert(c: x509.Certificate) -> str:
    return c.public_bytes(serialization.Encoding.PEM).decode()


def _pem_key(k) -> str:
    return k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()


def fingerprint(ca_pem: str) -> str:
    """SHA-256 of the CA's public key (what the phone pins)."""
    cert = x509.load_pem_x509_certificate(ca_pem.encode())
    spki = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(spki).hexdigest()


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _new_ca(host_id: str, now: dt.datetime, key=None):
    key = key or ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, f"Hubuddy {host_id}")])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - 5 * dt.timedelta(minutes=1))
            .not_valid_after(now + dt.timedelta(days=365 * CA_YEARS))
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=False, content_commitment=False, key_encipherment=False, data_encipherment=False,
                                         key_agreement=False, key_cert_sign=True, crl_sign=True, encipher_only=False, decipher_only=False), critical=True)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
            .sign(key, hashes.SHA256()))
    return key, cert


def _new_leaf(host_id: str, ca_key, ca_cert: x509.Certificate, now: dt.datetime):
    key = ec.generate_private_key(ec.SECP256R1())
    end = min(now + LEAF_DAYS * DAY, not_after(ca_cert))
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, tls_name(host_id))]))
            .issuer_name(ca_cert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(minutes=5)).not_valid_after(end)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False, data_encipherment=False,
                                         key_agreement=False, key_cert_sign=False, crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName(tls_name(host_id))]), critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_cert.public_key()), critical=False)
            .sign(ca_key, hashes.SHA256()))
    return key, cert


class ManagedTls:
    """Loads (or creates) this PC's identity and hands out server TLS contexts."""

    def __init__(self, home: pathlib.Path, host_id: str, clock=lambda: dt.datetime.now(dt.timezone.utc)):
        if not host_id:
            raise IdentityError("A persistent host identity is required.")
        self.home, self.host_id, self.clock = pathlib.Path(home), host_id, clock
        self.dir = private_dir(self.home / "identity")
        self._file = self.dir / "identity.json"
        self._marker = self.home / "identity.marker"
        self._lock = threading.Lock()
        self._rec: dict = {}
        self._ctx: ssl.SSLContext | None = None
        self._load_or_create()

    # ---- persistence ------------------------------------------------------------
    def _load_or_create(self):
        marker = None
        if self._marker.exists():
            try:
                marker = json.loads(self._marker.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                raise IdentityError("Hubuddy's identity record is unreadable. Restore it from a backup, or delete the "
                                    f"folder {self.home} to start over (phones must then be paired again).")
        if self._file.exists():
            try:
                self._rec = json.loads(self._file.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                raise IdentityError("Hubuddy's identity file is unreadable. Restore it from a backup, or delete the "
                                    f"folder {self.home} to start over (phones must then be paired again).")
        elif marker:
            raise IdentityError("Hubuddy's identity is missing but phones were already paired with it. Restore the identity "
                                "backup; creating a new one would silently break every paired phone.")
        else:
            now = self.clock()
            ca_key, ca = _new_ca(self.host_id, now)
            self._rec = {"version": 1, "hostId": self.host_id, "ca": _pem_cert(ca), "caKey": _pem_key(ca_key)}
            self._issue_leaf(now)
        self._validate(marker)
        self.refresh()

    def _validate(self, marker):
        r = self._rec
        if r.get("version") != 1 or r.get("hostId") != self.host_id:
            raise IdentityError("Hubuddy's identity does not belong to this computer id. Restore the matching identity.")
        try:
            ca = x509.load_pem_x509_certificate(r["ca"].encode())
            leaf = x509.load_pem_x509_certificate(r["cert"].encode())
            ca_key = serialization.load_pem_private_key(r["caKey"].encode(), None)
            leaf_key = serialization.load_pem_private_key(r["key"].encode(), None)
            issued_by(leaf, ca)
            ok = (ca.public_key().public_numbers() == ca_key.public_key().public_numbers()
                  and leaf.public_key().public_numbers() == leaf_key.public_key().public_numbers())
            names = leaf.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName)
        except Exception as e:
            raise IdentityError("Hubuddy's saved identity is damaged. Restore it from a backup.") from e
        if not ok or tls_name(self.host_id) not in names:
            raise IdentityError("Hubuddy's identity does not match its saved keys. Restore the matching identity.")
        pin = fingerprint(r["ca"])
        if marker and (marker.get("pin") != pin or marker.get("hostId") != self.host_id):
            raise IdentityError("This computer's established identity changed. Paired phones would no longer trust it; "
                                "restore the original identity.")
        if not marker:
            write_private_json(self._marker, {"version": 1, "hostId": self.host_id, "pin": pin})

    def _issue_leaf(self, now: dt.datetime):
        ca = x509.load_pem_x509_certificate(self._rec["ca"].encode())
        if not_after(ca) <= now + DAY:
            raise IdentityError("This computer's identity has expired. Restore its backup, or pair phones again with a new identity.")
        ca_key = serialization.load_pem_private_key(self._rec["caKey"].encode(), None)
        key, cert = _new_leaf(self.host_id, ca_key, ca, now)
        self._rec["cert"], self._rec["key"] = _pem_cert(cert), _pem_key(key)
        write_private_json(self._file, self._rec)

    # ---- renewal -------------------------------------------------------------------
    def refresh(self) -> bool:
        """Renew the server certificate (and the CA certificate, with the same key) when due. True if changed."""
        with self._lock:
            now = self.clock()
            ca = x509.load_pem_x509_certificate(self._rec["ca"].encode())
            if not_before(ca) > now:
                raise IdentityError("This computer's clock is earlier than its trusted identity. Fix the date and time.")
            changed = False
            if not_after(ca) <= now + RENEW_CA_BEFORE:
                key = serialization.load_pem_private_key(self._rec["caKey"].encode(), None)
                _, renewed = _new_ca(self.host_id, now, key)
                self._rec["ca"] = _pem_cert(renewed)
                changed = True
            leaf = x509.load_pem_x509_certificate(self._rec["cert"].encode())
            if changed or not_after(leaf) <= now + RENEW_LEAF_BEFORE or not_before(leaf) > now:
                self._issue_leaf(now)
                changed = True
            if changed or self._ctx is None:
                self._rebuild_context()
            return changed

    def _rebuild_context(self):
        chain, keyfile = self.dir / "server-chain.pem", self.dir / "server-key.pem"
        write_private(chain, self._rec["cert"] + "\n" + self._rec["ca"])
        write_private(keyfile, self._rec["key"])
        if self._ctx is None:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.minimum_version = ssl.TLSVersion.TLSv1_2
            self._ctx = ctx
        self._ctx.load_cert_chain(str(chain), str(keyfile))  # new connections pick up the renewed certificate

    # ---- what other parts need -------------------------------------------------------
    @property
    def context(self) -> ssl.SSLContext:
        return self._ctx

    @property
    def name(self) -> str:
        return tls_name(self.host_id)

    @property
    def ca_pem(self) -> str:
        return self._rec["ca"]

    @property
    def public_ca(self) -> str:
        """The CA certificate in the form carried by the invitation: base64url of its DER bytes."""
        return b64url(x509.load_pem_x509_certificate(self._rec["ca"].encode()).public_bytes(serialization.Encoding.DER))

    @property
    def pin(self) -> str:
        return fingerprint(self._rec["ca"])

    @property
    def diagnostics(self) -> dict:
        leaf = x509.load_pem_x509_certificate(self._rec["cert"].encode())
        ca = x509.load_pem_x509_certificate(self._rec["ca"].encode())
        return {"hostId": self.host_id, "tlsName": self.name, "identityDigest": self.pin,
                "certificateExpires": not_after(leaf).isoformat(), "authorityExpires": not_after(ca).isoformat()}


def client_context(ca_der_b64url: str) -> ssl.SSLContext:
    """What a phone does (used by the self-test and tests): trust only this CA, check the signed name."""
    der = base64.urlsafe_b64decode(ca_der_b64url + "=" * (-len(ca_der_b64url) % 4))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_verify_locations(cadata=ssl.DER_cert_to_PEM_cert(der))
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    return ctx
