"""Resource-oriented WSGI API."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import logging
import re
import threading
import time
from typing import Any, Callable, Iterable

from .config import RelayConfig
from .crypto import MessageCrypto, verify_pairing_proof, verify_request
from .errors import RelayError, failed_precondition, invalid_argument, not_found, unauthenticated
from .messages import MessagesAdapter
from .store import PairingRecord, RelayStore


LOGGER = logging.getLogger("android_imessage_relay")
PAIRING_PATH = re.compile(r"/v1/pairingRequests/([A-Za-z0-9_-]{16,128})\Z")


class RelayApplication:
    """Small WSGI application with bounded input and sanitized errors."""

    def __init__(
        self,
        config: RelayConfig,
        store: RelayStore,
        crypto: MessageCrypto,
        messages: MessagesAdapter,
    ) -> None:
        self.config = config
        self.store = store
        self.crypto = crypto
        self.messages = messages
        self._maintenance_lock = threading.Lock()
        self._maintenance_stop = threading.Event()
        self._maintenance_thread: threading.Thread | None = None

    def __call__(self, environ: dict[str, Any], start_response: Callable) -> Iterable[bytes]:
        try:
            self._ensure_maintenance()
            self._authorize(environ)
            status, resource = self._route(environ)
            return self._respond(start_response, status, resource)
        except RelayError as error:
            return self._error(start_response, error)
        except Exception:
            LOGGER.exception("request_failed")
            return self._error(start_response, RelayError(500, "INTERNAL", "An internal error occurred"))

    def _ensure_maintenance(self) -> None:
        if self._maintenance_thread is not None:
            return
        with self._maintenance_lock:
            if self._maintenance_thread is not None:
                return
            self.store.cleanup()
            self._maintenance_thread = threading.Thread(
                target=self._maintenance_loop,
                name="relay-maintenance",
                daemon=True,
            )
            self._maintenance_thread.start()

    def _maintenance_loop(self) -> None:
        interval = self.config.maintenance_interval_seconds
        while not self._maintenance_stop.wait(interval):
            try:
                self.store.cleanup()
            except Exception:
                LOGGER.exception("maintenance_failed")

    def close(self) -> None:
        self._maintenance_stop.set()
        thread = self._maintenance_thread
        if thread is not None:
            thread.join(timeout=1)

    def _authorize(self, environ: dict[str, Any]) -> None:
        if self.config.require_access_header and not environ.get("HTTP_CF_ACCESS_JWT_ASSERTION"):
            raise unauthenticated()

    def _route(self, environ: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        method = environ.get("REQUEST_METHOD", "")
        path = environ.get("PATH_INFO", "")
        if method == "GET" and path == "/v1/health":
            return 200, {"state": "SERVING"}
        if method == "POST" and path == "/v1/pairingRequests":
            return 200, self._create_pairing(self._json_body(environ))
        match = PAIRING_PATH.fullmatch(path)
        if method == "GET" and match:
            request = self.store.get_pairing(match.group(1))
            verify_request(
                request.device_public_key,
                method,
                path,
                b"",
                environ.get("HTTP_X_RELAY_SIGNATURE", ""),
            )
            return 200, self._pairing_resource(request)
        if method == "POST" and path == "/v1/messages":
            body = self._read_body(environ)
            return 200, self._create_message(body, environ.get("HTTP_X_RELAY_SIGNATURE", ""))
        raise not_found("Resource")

    def _create_pairing(self, body: dict[str, Any]) -> dict[str, Any]:
        public_key = body.get("devicePublicKey")
        verify_pairing_proof(public_key, body.get("nonce"), body.get("proof"))
        record = self.store.create_pairing(
            public_key, body.get("nonce"), self.config.pairing_ttl_seconds)
        return self._pairing_resource(record)

    def _create_message(self, body: bytes, signature: str) -> dict[str, Any]:
        public_key = self.store.bound_device_key()
        if public_key is None:
            raise failed_precondition("No device is paired")
        verify_request(public_key, "POST", "/v1/messages", body, signature)
        try:
            envelope = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise invalid_argument("Request body must be valid JSON") from error
        sent_at = envelope.get("sentAt")
        if not isinstance(sent_at, int) or abs(int(time.time()) - sent_at) > 300:
            raise invalid_argument("sentAt is outside the accepted time window")
        message_id = envelope.get("messageId")
        if not isinstance(message_id, str) or not re.fullmatch(r"[0-9a-f]{64}", message_id):
            raise invalid_argument("messageId is invalid")
        claim = self.store.claim_delivery(message_id, self.config.lease_seconds)
        if claim == "DUPLICATE":
            return {"name": f"messages/{message_id}", "state": "DELIVERED"}
        if claim == "BUSY":
            raise RelayError(409, "ABORTED", "Message delivery is already in progress")
        try:
            message = self.crypto.decrypt(envelope)
            self.messages.send(message["sender"], message["body"])
            self.store.complete_delivery(message_id)
        except Exception:
            self.store.fail_delivery(message_id, "DELIVERY_FAILED")
            raise
        return {"name": f"messages/{message_id}", "state": "DELIVERED"}

    def _json_body(self, environ: dict[str, Any]) -> dict[str, Any]:
        try:
            value = json.loads(self._read_body(environ))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise invalid_argument("Request body must be valid JSON") from error
        if not isinstance(value, dict):
            raise invalid_argument("Request body must be a JSON object")
        return value

    def _read_body(self, environ: dict[str, Any]) -> bytes:
        try:
            length = int(environ.get("CONTENT_LENGTH", "0"))
        except ValueError as error:
            raise invalid_argument("Content-Length is invalid") from error
        if length <= 0 or length > self.config.max_request_body:
            raise invalid_argument("Request body size is invalid")
        body = environ["wsgi.input"].read(length)
        if len(body) != length:
            raise invalid_argument("Request body is incomplete")
        return body

    def _pairing_resource(self, record: PairingRecord) -> dict[str, Any]:
        resource: dict[str, Any] = {
            "name": f"pairingRequests/{record.request_id}",
            "state": record.state,
            "displayCode": record.display_code,
            "createTime": self._timestamp(record.create_time),
            "expireTime": self._timestamp(record.expire_time),
        }
        if record.state == "APPROVED":
            resource["messageEncryptionPublicKey"] = self.crypto.public_key_base64
            resource["messageEncryptionKeyFingerprint"] = self.crypto.fingerprint
        return resource

    @staticmethod
    def _timestamp(value: int) -> str:
        return datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _respond(start_response: Callable, status: int, value: dict[str, Any]) -> list[bytes]:
        body = json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
        reasons = {
            200: "OK", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden",
            404: "Not Found", 409: "Conflict", 429: "Too Many Requests",
            500: "Internal Server Error",
        }
        start_response(f"{status} {reasons.get(status, 'Unknown')}", [
            ("Content-Type", "application/json; charset=utf-8"),
            ("Content-Length", str(len(body))),
            ("Cache-Control", "no-store"),
        ])
        return [body]

    @classmethod
    def _error(cls, start_response: Callable, error: RelayError) -> list[bytes]:
        code_by_name = {
            "INVALID_ARGUMENT": 3,
            "UNAUTHENTICATED": 16,
            "PERMISSION_DENIED": 7,
            "NOT_FOUND": 5,
            "FAILED_PRECONDITION": 9,
            "ABORTED": 10,
            "RESOURCE_EXHAUSTED": 8,
            "INTERNAL": 13,
        }
        return cls._respond(start_response, error.http_status, {"error": {
            "code": code_by_name.get(error.status, 2),
            "status": error.status,
            "message": error.message,
        }})
