"""In-process key management, signature verification, and decryption."""

from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .errors import invalid_argument, permission_denied


def _decode(value: str, label: str, maximum: int) -> bytes:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise invalid_argument(f"{label} is invalid")
    try:
        return base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as error:
        raise invalid_argument(f"{label} is invalid") from error


def parse_device_public_key(encoded: str) -> ec.EllipticCurvePublicKey:
    try:
        key = serialization.load_der_public_key(_decode(encoded, "devicePublicKey", 4096))
    except (ValueError, TypeError) as error:
        raise invalid_argument("devicePublicKey is invalid") from error
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise invalid_argument("devicePublicKey must use P-256")
    return key


def verify_pairing_proof(public_key_text: str, nonce: str, proof: str) -> None:
    if not isinstance(nonce, str) or len(nonce) < 32 or len(nonce) > 128:
        raise invalid_argument("nonce is invalid")
    key = parse_device_public_key(public_key_text)
    signed = f"PAIRING_V1\n{public_key_text}\n{nonce}".encode("utf-8")
    _verify(key, signed, proof)


def verify_request(public_key_text: str, method: str, path: str, body: bytes, signature: str) -> None:
    digest = hashlib.sha256(body).hexdigest()
    signed = f"{method}\n{path}\n{digest}".encode("utf-8")
    _verify(parse_device_public_key(public_key_text), signed, signature)


def _verify(key: ec.EllipticCurvePublicKey, body: bytes, signature_text: str) -> None:
    try:
        signature = _decode(signature_text, "signature", 1024)
        key.verify(signature, body, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError) as error:
        raise permission_denied() from error


class MessageCrypto:
    """Owns the relay RSA key and decrypts message envelopes."""

    def __init__(self, data_dir: Path) -> None:
        self.private_path = data_dir / "message-private-key.pem"
        self.public_path = data_dir / "message-public-key.der"
        self._private_key = self._load_or_create()

    def _load_or_create(self) -> rsa.RSAPrivateKey:
        if self.private_path.exists():
            key = serialization.load_pem_private_key(self.private_path.read_bytes(), password=None)
            if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 3072:
                raise RuntimeError("The relay RSA private key is invalid")
            return key
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        private_bytes = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        public_bytes = key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        self._atomic_write(self.private_path, private_bytes)
        self._atomic_write(self.public_path, public_bytes)
        return key

    @staticmethod
    def _atomic_write(path: Path, value: bytes) -> None:
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "wb", closefd=True) as stream:
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary_name, path)
        except Exception:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
            raise

    @property
    def public_key_base64(self) -> str:
        encoded = self._private_key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        return base64.b64encode(encoded).decode("ascii")

    @property
    def fingerprint(self) -> str:
        return hashlib.sha256(base64.b64decode(self.public_key_base64)).hexdigest()

    def decrypt(self, envelope: dict[str, Any]) -> dict[str, str]:
        message_id = envelope.get("messageId")
        sent_at = envelope.get("sentAt")
        if not isinstance(message_id, str) or len(message_id) != 64 \
                or any(character not in "0123456789abcdef" for character in message_id):
            raise invalid_argument("messageId is invalid")
        if not isinstance(sent_at, int) or sent_at <= 0:
            raise invalid_argument("sentAt is invalid")
        encrypted_key = _decode(envelope.get("encryptedKey"), "encryptedKey", 2048)
        iv = _decode(envelope.get("iv"), "iv", 64)
        ciphertext = _decode(envelope.get("ciphertext"), "ciphertext", 96 * 1024)
        if len(iv) != 12:
            raise invalid_argument("iv is invalid")
        try:
            message_key = self._private_key.decrypt(
                encrypted_key,
                padding.OAEP(
                    mgf=padding.MGF1(algorithm=hashes.SHA256()),
                    algorithm=hashes.SHA256(),
                    label=None,
                ),
            )
            plaintext = AESGCM(message_key).decrypt(
                iv, ciphertext, f"{message_id}\n{sent_at}".encode("utf-8"))
            payload = json.loads(plaintext)
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            raise invalid_argument("The encrypted message is invalid") from error
        sender = payload.get("sender")
        body = payload.get("body")
        if not isinstance(sender, str) or not sender or "\n" in sender or "\r" in sender \
                or len(sender.encode("utf-8")) > 1024:
            raise invalid_argument("The encrypted sender is invalid")
        if not isinstance(body, str) or not body or len(body.encode("utf-8")) > 32 * 1024:
            raise invalid_argument("The encrypted message body is invalid")
        return {"sender": sender, "body": body}
