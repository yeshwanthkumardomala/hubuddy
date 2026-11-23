"""Regenerates the throw-away identities used by the Android unit tests (app/src/test/resources/pki).

Each identity is a private CA plus a server certificate with the signed name host-<id>.hubuddy.invalid, exactly the
shape the PC program issues, but valid for decades so the tests never expire. They are test fixtures, not secrets:
they protect nothing and are never used outside the tests.

    python tools/make_test_pki.py
"""
import base64
import datetime as dt
import pathlib

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

OUT = pathlib.Path(__file__).resolve().parent.parent / "android/hubuddy/app/src/test/resources/pki"
IDENTITIES = {"a": "0f8c6a52-3a1d-4b7e-9c11-2e5d7f3a9b01", "b": "7d21c4e0-95aa-4c3b-8f66-1b0e2d9c4a77"}


def name(cn):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def make(tag, host_id):
    start = dt.datetime(2025, 1, 1, tzinfo=dt.timezone.utc)
    end = dt.datetime(2095, 1, 1, tzinfo=dt.timezone.utc)
    ca_key, key = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    ca = (x509.CertificateBuilder().subject_name(name(f"Hubuddy test CA {tag}")).issuer_name(name(f"Hubuddy test CA {tag}"))
          .public_key(ca_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(start).not_valid_after(end)
          .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True).sign(ca_key, hashes.SHA256()))
    leaf = (x509.CertificateBuilder().subject_name(name("Hubuddy test PC")).issuer_name(ca.subject).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(start).not_valid_after(end)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName(f"host-{host_id}.hubuddy.invalid")]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    d = OUT / tag
    d.mkdir(parents=True, exist_ok=True)
    (d / "host-id.txt").write_text(host_id)
    (d / "ca.b64").write_text(base64.urlsafe_b64encode(ca.public_bytes(serialization.Encoding.DER)).rstrip(b"=").decode())
    (d / "leaf.pem").write_bytes(leaf.public_bytes(serialization.Encoding.PEM))
    (d / "key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))


if __name__ == "__main__":
    for tag, hid in IDENTITIES.items():
        make(tag, hid)
    print("wrote", OUT)
