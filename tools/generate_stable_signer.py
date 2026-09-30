from __future__ import annotations

import datetime
import hashlib
import math
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

SEED = b"Photo-TV stable development signer v1"
STORE_PASSWORD = b"phototv-stable"
ALIAS = b"phototv"
EXPECTED_SHA256 = "8480f37e2123c7c93ada4dbedef25e0f0f80ec689575c9e75ddc174c43c62d06"
PUBLIC_EXPONENT = 65537

class DeterministicBytes:
    def __init__(self, seed: bytes):
        self.seed = seed
        self.counter = 0

    def get(self, length: int) -> bytes:
        out = bytearray()
        while len(out) < length:
            out.extend(
                hashlib.sha512(
                    self.seed + self.counter.to_bytes(8, "big")
                ).digest()
            )
            self.counter += 1
        return bytes(out[:length])


rng = DeterministicBytes(SEED)


def randbelow(n: int) -> int:
    bits = n.bit_length()
    byte_count = (bits + 7) // 8
    while True:
        value = int.from_bytes(rng.get(byte_count), "big")
        value &= (1 << bits) - 1
        if value < n:
            return value


SMALL_PRIMES = (3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47)


def is_probable_prime(n: int, rounds: int = 32) -> bool:
    if n < 2:
        return False
    for p in SMALL_PRIMES:
        if n == p:
            return True
        if n % p == 0:
            return False

    d = n - 1
    s = 0
    while d % 2 == 0:
        s += 1
        d //= 2

    for _ in range(rounds):
        a = 2 + randbelow(n - 3)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def generate_prime(bits: int) -> int:
    while True:
        candidate = int.from_bytes(rng.get(bits // 8), "big")
        candidate |= (1 << (bits - 1)) | 1
        if math.gcd(candidate - 1, PUBLIC_EXPONENT) != 1:
            continue
        if is_probable_prime(candidate):
            return candidate


def build_private_key() -> rsa.RSAPrivateKey:
    p = generate_prime(1024)
    q = generate_prime(1024)
    while q == p:
        q = generate_prime(1024)

    n = p * q
    phi = (p - 1) * (q - 1)
    d = pow(PUBLIC_EXPONENT, -1, phi)

    numbers = rsa.RSAPrivateNumbers(
        p=p,
        q=q,
        d=d,
        dmp1=d % (p - 1),
        dmq1=d % (q - 1),
        iqmp=pow(q, -1, p),
        public_numbers=rsa.RSAPublicNumbers(PUBLIC_EXPONENT, n),
    )
    return numbers.private_key()


def build_certificate(key: rsa.RSAPrivateKey) -> x509.Certificate:
    subject = x509.Name(
        [x509.NameAttribute(NameOID.COMMON_NAME, "Photo TV Stable Development")]
    )
    serial = int.from_bytes(
        hashlib.sha256(SEED + b":serial").digest()[:20], "big"
    ) >> 1

    return (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(serial)
        .not_valid_before(datetime.datetime(2025, 1, 1, tzinfo=datetime.timezone.utc))
        .not_valid_after(datetime.datetime(2050, 1, 1, tzinfo=datetime.timezone.utc))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build-keys/photo-tv-stable.p12")
    output.parent.mkdir(parents=True, exist_ok=True)

    key = build_private_key()
    cert = build_certificate(key)
    fingerprint = cert.fingerprint(hashes.SHA256()).hex()
    if fingerprint != EXPECTED_SHA256:
        raise SystemExit(
            f"Stable signer fingerprint changed: {fingerprint} != {EXPECTED_SHA256}"
        )

    payload = pkcs12.serialize_key_and_certificates(
        ALIAS,
        key,
        cert,
        None,
        serialization.BestAvailableEncryption(STORE_PASSWORD),
    )
    output.write_bytes(payload)
    print(f"stable signer: {fingerprint}")
    print(f"wrote {output}")


if __name__ == "__main__":
    main()
