"""CLI security-boundary tests."""

import pytest

from android_imessage_relay.cli import _validate_loopback_bind


@pytest.mark.parametrize("value", ["127.0.0.1:8443", "[::1]:8443"])
def test_tcp_mode_accepts_loopback_addresses(value):
    assert _validate_loopback_bind(value) == value


@pytest.mark.parametrize("value", ["0.0.0.0:8443", "192.0.2.10:8443", "relay.example.com:8443"])
def test_tcp_mode_rejects_non_loopback_addresses(value):
    with pytest.raises(SystemExit, match="loopback"):
        _validate_loopback_bind(value)
