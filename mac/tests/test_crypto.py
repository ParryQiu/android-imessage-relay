"""Cryptographic protocol tests."""

import base64
import json
import time

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from android_imessage_relay.crypto import MessageCrypto, verify_pairing_proof, verify_request
from android_imessage_relay.errors import RelayError


def _device_key():
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_text = base64.b64encode(private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )).decode("ascii")
    return private_key, public_text


def _sign(private_key, value: bytes) -> str:
    return base64.b64encode(private_key.sign(value, ec.ECDSA(hashes.SHA256()))).decode("ascii")


def test_pairing_proof_and_request_signature_are_bound_to_context():
    private_key, public_text = _device_key()
    nonce = base64.urlsafe_b64encode(b"n" * 32).rstrip(b"=").decode("ascii")
    proof = _sign(private_key, f"PAIRING_V1\n{public_text}\n{nonce}".encode())
    verify_pairing_proof(public_text, nonce, proof)
    body = b"{}"
    signed = f"POST\n/v1/messages\n{__import__('hashlib').sha256(body).hexdigest()}".encode()
    signature = _sign(private_key, signed)
    verify_request(public_text, "POST", "/v1/messages", body, signature)
    with pytest.raises(RelayError):
        verify_request(public_text, "POST", "/v1/messages", b"changed", signature)


def test_message_envelope_decrypts_in_process(tmp_path):
    crypto = MessageCrypto(tmp_path)
    message_key = AESGCM.generate_key(bit_length=256)
    message_id = "c" * 64
    sent_at = int(time.time())
    iv = b"i" * 12
    plaintext = json.dumps({"sender": "Example sender", "body": "Code 123456"}).encode()
    ciphertext = AESGCM(message_key).encrypt(iv, plaintext, f"{message_id}\n{sent_at}".encode())
    encrypted_key = crypto._private_key.public_key().encrypt(
        message_key,
        padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )
    result = crypto.decrypt({
        "messageId": message_id,
        "sentAt": sent_at,
        "encryptedKey": base64.b64encode(encrypted_key).decode(),
        "iv": base64.b64encode(iv).decode(),
        "ciphertext": base64.b64encode(ciphertext).decode(),
    })
    assert result == {"sender": "Example sender", "body": "Code 123456"}
    assert crypto.private_path.stat().st_mode & 0o777 == 0o600
