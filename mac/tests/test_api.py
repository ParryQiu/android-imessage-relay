"""Public API behavior tests."""

import base64
from io import BytesIO
import json

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from android_imessage_relay.api import RelayApplication
from android_imessage_relay.config import RelayConfig
from android_imessage_relay.crypto import MessageCrypto
from android_imessage_relay.store import RelayStore


class StubMessages:
    def send(self, sender, body):
        raise AssertionError("No message should be delivered in this test")


def _request(application, method, path, body=b"", headers=None):
    response = {}

    def start_response(status, values):
        response["status"] = int(status.split()[0])
        response["headers"] = dict(values)

    environ = {
        "REQUEST_METHOD": method,
        "PATH_INFO": path,
        "CONTENT_LENGTH": str(len(body)),
        "wsgi.input": BytesIO(body),
    }
    for key, value in (headers or {}).items():
        environ["HTTP_" + key.upper().replace("-", "_")] = value
    payload = b"".join(application(environ, start_response))
    return response["status"], json.loads(payload)


def _application(tmp_path, require_access=True):
    config = RelayConfig(
        data_dir=tmp_path,
        socket_path=tmp_path / "relay.sock",
        messages_script=tmp_path / "messages.scpt",
        recipient_helper=tmp_path / "recipient",
        require_access_header=require_access,
    )
    return RelayApplication(
        config,
        RelayStore(tmp_path / "relay.sqlite3"),
        MessageCrypto(tmp_path),
        StubMessages(),
    )


def _device():
    private = ec.generate_private_key(ec.SECP256R1())
    public = base64.b64encode(private.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )).decode("ascii")
    return private, public


def _sign(private, value):
    return base64.b64encode(private.sign(value, ec.ECDSA(hashes.SHA256()))).decode("ascii")


def test_access_header_is_required_and_errors_are_sanitized(tmp_path):
    application = _application(tmp_path)
    status, body = _request(application, "GET", "/v1/health")
    assert status == 401
    assert body == {"error": {
        "code": 16,
        "status": "UNAUTHENTICATED",
        "message": "Authentication is required",
    }}
    assert str(tmp_path) not in json.dumps(body)


def test_pairing_requires_proof_and_local_approval(tmp_path):
    application = _application(tmp_path)
    private, public = _device()
    nonce = base64.urlsafe_b64encode(b"x" * 32).rstrip(b"=").decode()
    proof = _sign(private, f"PAIRING_V1\n{public}\n{nonce}".encode())
    request_body = json.dumps({
        "devicePublicKey": public,
        "nonce": nonce,
        "proof": proof,
    }, separators=(",", ":")).encode()
    status, pairing = _request(
        application,
        "POST",
        "/v1/pairingRequests",
        request_body,
        {"Cf-Access-Jwt-Assertion": "test-access"},
    )
    assert status == 200
    assert pairing["state"] == "PENDING"
    request_id = pairing["name"].split("/", 1)[1]
    path = f"/v1/pairingRequests/{request_id}"
    digest = __import__("hashlib").sha256(b"").hexdigest()
    signature = _sign(private, f"GET\n{path}\n{digest}".encode())
    status, pending = _request(application, "GET", path, headers={
        "Cf-Access-Jwt-Assertion": "test-access",
        "X-Relay-Signature": signature,
    })
    assert status == 200
    assert pending["state"] == "PENDING"
    application.store.approve_pairing(request_id)
    status, approved = _request(application, "GET", path, headers={
        "Cf-Access-Jwt-Assertion": "test-access",
        "X-Relay-Signature": signature,
    })
    assert status == 200
    assert approved["state"] == "APPROVED"
    assert len(approved["messageEncryptionKeyFingerprint"]) == 64


def test_pairing_rejects_invalid_proof(tmp_path):
    application = _application(tmp_path)
    _, public = _device()
    body = json.dumps({
        "devicePublicKey": public,
        "nonce": base64.urlsafe_b64encode(b"x" * 32).decode(),
        "proof": base64.b64encode(b"invalid").decode(),
    }).encode()
    status, response = _request(application, "POST", "/v1/pairingRequests", body, {
        "Cf-Access-Jwt-Assertion": "test-access",
    })
    assert status == 403
    assert response["error"]["status"] == "PERMISSION_DENIED"
