"""Bounded SQLite state for pairing and idempotent deliveries."""

from __future__ import annotations

import secrets
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from .errors import failed_precondition, not_found, resource_exhausted


PAIRING_STATES = {"PENDING", "APPROVED", "REJECTED", "EXPIRED"}


@dataclass(frozen=True)
class PairingRecord:
    request_id: str
    device_public_key: str
    display_code: str
    state: str
    create_time: int
    expire_time: int


class RelayStore:
    """Serializes security-sensitive state transitions through SQLite."""

    def __init__(
        self,
        database_path: Path,
        max_deliveries: int = 10_000,
        max_pairing_requests: int = 1_000,
    ) -> None:
        self.database_path = database_path
        self.max_deliveries = max_deliveries
        self.max_pairing_requests = max_pairing_requests
        self.database_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self._initialize()
        self.database_path.chmod(0o600)

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=5)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA busy_timeout=5000")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS pairing_requests (
                    request_id TEXT PRIMARY KEY,
                    device_public_key TEXT NOT NULL,
                    nonce TEXT NOT NULL UNIQUE,
                    display_code TEXT NOT NULL,
                    state TEXT NOT NULL,
                    create_time INTEGER NOT NULL,
                    expire_time INTEGER NOT NULL,
                    used_at INTEGER
                );
                CREATE TABLE IF NOT EXISTS device_binding (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    device_public_key TEXT NOT NULL,
                    paired_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS deliveries (
                    message_id TEXT PRIMARY KEY,
                    received_at INTEGER NOT NULL,
                    delivered_at INTEGER,
                    status TEXT NOT NULL,
                    lease_until INTEGER,
                    error_code TEXT
                );
                CREATE INDEX IF NOT EXISTS deliveries_retention_idx
                    ON deliveries(received_at);
                """
            )

    def create_pairing(
        self,
        device_public_key: str,
        nonce: str,
        ttl_seconds: int,
        now: Optional[int] = None,
    ) -> PairingRecord:
        current = int(time.time()) if now is None else now
        request_id = secrets.token_urlsafe(24)
        display_code = self._display_code(device_public_key)
        try:
            with self._connect() as connection:
                connection.execute("BEGIN IMMEDIATE")
                self._cleanup(connection, current)
                count = connection.execute(
                    "SELECT COUNT(*) FROM pairing_requests"
                ).fetchone()[0]
                if count >= self.max_pairing_requests:
                    raise resource_exhausted()
                connection.execute(
                    "INSERT INTO pairing_requests VALUES (?, ?, ?, ?, 'PENDING', ?, ?, NULL)",
                    (request_id, device_public_key, nonce, display_code,
                     current, current + ttl_seconds),
                )
        except sqlite3.IntegrityError as error:
            raise failed_precondition("Pairing proof has already been used") from error
        return self.get_pairing(request_id, current)

    def get_pairing(self, request_id: str, now: Optional[int] = None) -> PairingRecord:
        current = int(time.time()) if now is None else now
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM pairing_requests WHERE request_id = ?", (request_id,)
            ).fetchone()
            if row is None:
                raise not_found("Pairing request")
            if row["state"] == "PENDING" and row["expire_time"] <= current:
                connection.execute(
                    "UPDATE pairing_requests SET state = 'EXPIRED' "
                    "WHERE request_id = ? AND state = 'PENDING'", (request_id,)
                )
                row = connection.execute(
                    "SELECT * FROM pairing_requests WHERE request_id = ?", (request_id,)
                ).fetchone()
        return PairingRecord(
            row["request_id"], row["device_public_key"], row["display_code"],
            row["state"], row["create_time"], row["expire_time"],
        )

    def approve_pairing(self, request_id: str, replace: bool = False, now: Optional[int] = None) -> PairingRecord:
        current = int(time.time()) if now is None else now
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT * FROM pairing_requests WHERE request_id = ?", (request_id,)
            ).fetchone()
            if row is None:
                raise not_found("Pairing request")
            if row["state"] != "PENDING" or row["expire_time"] <= current:
                if row["state"] == "PENDING":
                    connection.execute(
                        "UPDATE pairing_requests SET state = 'EXPIRED' WHERE request_id = ?",
                        (request_id,),
                    )
                raise failed_precondition("Pairing request is no longer pending")
            binding = connection.execute(
                "SELECT device_public_key FROM device_binding WHERE singleton = 1"
            ).fetchone()
            if binding is not None and binding["device_public_key"] != row["device_public_key"] and not replace:
                raise failed_precondition("A different device is already paired")
            connection.execute(
                "INSERT INTO device_binding VALUES (1, ?, ?) "
                "ON CONFLICT(singleton) DO UPDATE SET device_public_key=excluded.device_public_key, "
                "paired_at=excluded.paired_at",
                (row["device_public_key"], current),
            )
            connection.execute(
                "UPDATE pairing_requests SET state = 'APPROVED', used_at = ? WHERE request_id = ?",
                (current, request_id),
            )
        return self.get_pairing(request_id, current)

    def reject_pairing(self, request_id: str, now: Optional[int] = None) -> PairingRecord:
        current = int(time.time()) if now is None else now
        with self._connect() as connection:
            updated = connection.execute(
                "UPDATE pairing_requests SET state = 'REJECTED', used_at = ? "
                "WHERE request_id = ? AND state = 'PENDING' AND expire_time > ?",
                (current, request_id, current),
            ).rowcount
        if updated != 1:
            raise failed_precondition("Pairing request is no longer pending")
        return self.get_pairing(request_id, current)

    def bound_device_key(self) -> Optional[str]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT device_public_key FROM device_binding WHERE singleton = 1"
            ).fetchone()
        return None if row is None else str(row["device_public_key"])

    def pending_pairings(self, now: Optional[int] = None) -> list[PairingRecord]:
        current = int(time.time()) if now is None else now
        with self._connect() as connection:
            connection.execute(
                "UPDATE pairing_requests SET state = 'EXPIRED' "
                "WHERE state = 'PENDING' AND expire_time <= ?", (current,)
            )
            rows = connection.execute(
                "SELECT * FROM pairing_requests WHERE state = 'PENDING' "
                "ORDER BY create_time ASC"
            ).fetchall()
        return [PairingRecord(
            row["request_id"], row["device_public_key"], row["display_code"],
            row["state"], row["create_time"], row["expire_time"],
        ) for row in rows]

    def resolve_pairing_id(self, value: str, now: Optional[int] = None) -> str:
        if len(value) >= 16:
            return value.removeprefix("pairingRequests/")
        matches = [record.request_id for record in self.pending_pairings(now)
                   if record.display_code.casefold() == value.casefold()]
        if len(matches) != 1:
            raise not_found("Pairing request")
        return matches[0]

    def claim_delivery(self, message_id: str, lease_seconds: int, now: Optional[int] = None) -> str:
        current = int(time.time()) if now is None else now
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._cleanup(connection, current)
            row = connection.execute(
                "SELECT status, lease_until FROM deliveries WHERE message_id = ?", (message_id,)
            ).fetchone()
            if row is not None and row["status"] == "DELIVERED":
                return "DUPLICATE"
            if row is not None and row["status"] == "IN_PROGRESS" \
                    and (row["lease_until"] or 0) > current:
                return "BUSY"
            count = connection.execute("SELECT COUNT(*) FROM deliveries").fetchone()[0]
            if row is None and count >= self.max_deliveries:
                raise resource_exhausted()
            connection.execute(
                "INSERT INTO deliveries VALUES (?, ?, NULL, 'IN_PROGRESS', ?, NULL) "
                "ON CONFLICT(message_id) DO UPDATE SET status='IN_PROGRESS', "
                "lease_until=excluded.lease_until, error_code=NULL",
                (message_id, current, current + lease_seconds),
            )
        return "CLAIMED"

    def complete_delivery(self, message_id: str, now: Optional[int] = None) -> None:
        current = int(time.time()) if now is None else now
        with self._connect() as connection:
            connection.execute(
                "UPDATE deliveries SET status='DELIVERED', delivered_at=?, lease_until=NULL "
                "WHERE message_id=?", (current, message_id)
            )

    def fail_delivery(self, message_id: str, error_code: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "UPDATE deliveries SET status='FAILED', lease_until=NULL, error_code=? "
                "WHERE message_id=?", (error_code[:64], message_id)
            )

    def cleanup(self, now: Optional[int] = None) -> None:
        current = int(time.time()) if now is None else now
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._cleanup(connection, current)

    @staticmethod
    def _cleanup(connection: sqlite3.Connection, now: int) -> None:
        connection.execute("DELETE FROM deliveries WHERE received_at < ?", (now - 30 * 86400,))
        connection.execute("DELETE FROM pairing_requests WHERE expire_time < ?", (now - 86400,))

    @staticmethod
    def _display_code(device_public_key: str) -> str:
        import hashlib

        digest = hashlib.sha256(device_public_key.encode("ascii")).hexdigest().upper()
        return f"{digest[:4]}-{digest[4:8]}"
