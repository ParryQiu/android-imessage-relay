"""Apple Messages delivery with a Keychain recipient and ephemeral FIFO."""

from __future__ import annotations

import errno
import os
from pathlib import Path
import re
import select
import subprocess
import tempfile
import time


RECIPIENT_PATTERN = re.compile(r"[+0-9A-Za-z@._-]{5,254}\Z")


class MessagesAdapter:
    """Delivers messages without placing their content in arguments or regular files."""

    def __init__(
        self,
        script_path: Path,
        recipient_helper: Path,
        dry_run: bool = False,
        osascript_path: Path = Path("/usr/bin/osascript"),
        delivery_timeout: float = 30,
    ) -> None:
        self.script_path = script_path
        self.recipient_helper = recipient_helper
        self.dry_run = dry_run
        self.osascript_path = osascript_path
        self.delivery_timeout = delivery_timeout

    def recipient(self) -> str:
        result = subprocess.run(
            [str(self.recipient_helper), "get"],
            check=True,
            capture_output=True,
            timeout=10,
        )
        value = result.stdout.decode("utf-8").strip()
        if not RECIPIENT_PATTERN.fullmatch(value):
            raise RuntimeError("The Keychain recipient is invalid")
        return value

    def send(self, sender: str, body: str) -> None:
        if "\x00" in sender or "\x00" in body or "\n" in sender or "\r" in sender:
            raise ValueError("Message contains unsupported characters")
        recipient = self.recipient()
        if self.dry_run:
            return
        with tempfile.TemporaryDirectory(prefix="android-imessage-relay-") as directory:
            directory_path = Path(directory)
            directory_path.chmod(0o700)
            fifo = directory_path / "message.fifo"
            os.mkfifo(fifo, 0o600)
            process = subprocess.Popen(
                [str(self.osascript_path), str(self.script_path), str(fifo)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            deadline = time.monotonic() + self.delivery_timeout
            try:
                message = f"From: {sender}\n{body}".encode("utf-8")
                payload = recipient.encode("utf-8") + b"\n" + message
                self._write_fifo(process, fifo, payload, deadline)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(process.args, self.delivery_timeout)
                process.communicate(timeout=remaining)
            except Exception:
                process.kill()
                process.wait(timeout=5)
                raise
            if process.returncode != 0:
                raise RuntimeError(f"Messages delivery failed with code {process.returncode}") from None

    def _write_fifo(
        self,
        process: subprocess.Popen,
        fifo: Path,
        payload: bytes,
        deadline: float,
    ) -> None:
        descriptor: int | None = None
        try:
            while descriptor is None:
                if process.poll() is not None:
                    raise RuntimeError("Messages helper exited before opening the FIFO")
                if time.monotonic() >= deadline:
                    raise subprocess.TimeoutExpired(process.args, self.delivery_timeout)
                try:
                    descriptor = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
                except OSError as error:
                    if error.errno != errno.ENXIO:
                        raise
                    time.sleep(0.01)

            pending = memoryview(payload)
            while pending:
                if process.poll() is not None:
                    raise RuntimeError("Messages helper exited while reading the FIFO")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(process.args, self.delivery_timeout)
                _, writable, _ = select.select([], [descriptor], [], min(0.1, remaining))
                if writable:
                    try:
                        written = os.write(descriptor, pending)
                    except BlockingIOError:
                        continue
                    pending = pending[written:]
        finally:
            if descriptor is not None:
                os.close(descriptor)
