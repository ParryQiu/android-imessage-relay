"""FIFO and Keychain adapter tests."""

from pathlib import Path
import subprocess
import time

import pytest

from android_imessage_relay.messages import MessagesAdapter


def test_fifo_carries_message_without_regular_plaintext_file(tmp_path):
    recipient = tmp_path / "recipient-helper"
    recipient.write_text("#!/bin/sh\nprintf 'relay-test@example.com'\n", encoding="utf-8")
    recipient.chmod(0o700)
    reader = tmp_path / "fake-osascript"
    output = tmp_path / "captured"
    reader.write_text(
        "#!/bin/sh\nfifo=$2\n/usr/bin/tee '" + str(output) + "' < \"$fifo\" >/dev/null\n",
        encoding="utf-8",
    )
    reader.chmod(0o700)
    adapter = MessagesAdapter(Path("unused"), recipient, osascript_path=reader)
    adapter.send("Example sender", "Code 123456")
    assert output.read_text(encoding="utf-8") == (
        "relay-test@example.com\nFrom: Example sender\nCode 123456"
    )


def test_fifo_reader_exit_does_not_block_a_worker(tmp_path):
    recipient = tmp_path / "recipient-helper"
    recipient.write_text("#!/bin/sh\nprintf 'relay-test@example.com'\n", encoding="utf-8")
    recipient.chmod(0o700)
    reader = tmp_path / "failed-osascript"
    reader.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
    reader.chmod(0o700)
    adapter = MessagesAdapter(
        Path("unused"),
        recipient,
        osascript_path=reader,
        delivery_timeout=0.5,
    )

    started = time.monotonic()
    with pytest.raises((RuntimeError, subprocess.TimeoutExpired)):
        adapter.send("Example sender", "Synthetic message")
    assert time.monotonic() - started < 1
