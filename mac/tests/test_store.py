"""State-transition tests."""

from concurrent.futures import ThreadPoolExecutor

import pytest

from android_imessage_relay.errors import RelayError
from android_imessage_relay.store import RelayStore


def test_pairing_expires_and_cannot_be_replayed(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3")
    request = store.create_pairing("device-key", "nonce-1", 10, now=100)
    assert store.get_pairing(request.request_id, now=111).state == "EXPIRED"
    with pytest.raises(RelayError) as error:
        store.approve_pairing(request.request_id, now=111)
    assert error.value.status == "FAILED_PRECONDITION"


def test_concurrent_pairing_has_one_winner(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3")
    request = store.create_pairing("device-key", "nonce-1", 60, now=100)

    def approve():
        try:
            return store.approve_pairing(request.request_id, now=101).state
        except RelayError as error:
            return error.status

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(lambda _: approve(), range(2)))
    assert sorted(results) == ["APPROVED", "FAILED_PRECONDITION"]


def test_replacing_device_requires_explicit_recovery(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3")
    first = store.create_pairing("first-device", "nonce-1", 60, now=100)
    store.approve_pairing(first.request_id, now=101)
    second = store.create_pairing("second-device", "nonce-2", 60, now=102)
    with pytest.raises(RelayError):
        store.approve_pairing(second.request_id, now=103)
    assert store.approve_pairing(second.request_id, replace=True, now=103).state == "APPROVED"
    assert store.bound_device_key() == "second-device"


def test_delivery_lease_recovers_and_idempotency_holds(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3")
    message_id = "a" * 64
    assert store.claim_delivery(message_id, 60, now=100) == "CLAIMED"
    assert store.claim_delivery(message_id, 60, now=120) == "BUSY"
    assert store.claim_delivery(message_id, 60, now=161) == "CLAIMED"
    store.complete_delivery(message_id, now=162)
    assert store.claim_delivery(message_id, 60, now=163) == "DUPLICATE"


def test_pairing_proof_cannot_be_replayed(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3")
    store.create_pairing("device-key", "unique-nonce", 60, now=100)
    with pytest.raises(RelayError) as error:
        store.create_pairing("device-key", "unique-nonce", 60, now=101)
    assert error.value.status == "FAILED_PRECONDITION"


def test_pairing_store_purges_expired_rows_before_enforcing_capacity(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3", max_pairing_requests=1)
    store.create_pairing("first-device", "nonce-1", 10, now=100)
    replacement = store.create_pairing("second-device", "nonce-2", 10, now=86_511)
    assert replacement.state == "PENDING"


def test_pairing_store_rejects_requests_at_capacity(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3", max_pairing_requests=1)
    store.create_pairing("first-device", "nonce-1", 60, now=100)
    with pytest.raises(RelayError) as error:
        store.create_pairing("second-device", "nonce-2", 60, now=101)
    assert error.value.status == "RESOURCE_EXHAUSTED"


def test_cleanup_removes_expired_metadata_without_a_new_delivery(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3")
    request = store.create_pairing("device-key", "nonce-1", 10, now=100)
    store.claim_delivery("a" * 64, 60, now=100)
    store.cleanup(now=31 * 86_400)
    with pytest.raises(RelayError) as error:
        store.get_pairing(request.request_id, now=31 * 86_400)
    assert error.value.status == "NOT_FOUND"
    assert store.claim_delivery("a" * 64, 60, now=31 * 86_400) == "CLAIMED"


def test_delivery_store_enforces_capacity(tmp_path):
    store = RelayStore(tmp_path / "relay.sqlite3", max_deliveries=1)
    store.claim_delivery("a" * 64, 60, now=100)
    with pytest.raises(RelayError) as error:
        store.claim_delivery("b" * 64, 60, now=101)
    assert error.value.status == "RESOURCE_EXHAUSTED"
