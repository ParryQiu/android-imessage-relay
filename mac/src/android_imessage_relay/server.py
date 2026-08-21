"""Production server assembly and bounded Gunicorn configuration."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

from gunicorn.app.base import BaseApplication

from .api import RelayApplication
from .config import RelayConfig
from .crypto import MessageCrypto
from .messages import MessagesAdapter
from .store import RelayStore


def build_application(config: RelayConfig) -> RelayApplication:
    config.prepare()
    store = RelayStore(
        config.data_dir / "relay.sqlite3",
        config.max_deliveries,
        config.max_pairing_requests,
    )
    store.cleanup()
    return RelayApplication(
        config,
        store,
        MessageCrypto(config.data_dir),
        MessagesAdapter(config.messages_script, config.recipient_helper, config.dry_run),
    )


class RelayServer(BaseApplication):
    """Runs one process with a fixed-size thread pool and strict limits."""

    def __init__(
        self,
        application: RelayApplication,
        bind: str,
        threads: int,
        timeout: int,
        certfile: Optional[Path] = None,
        keyfile: Optional[Path] = None,
    ) -> None:
        self.application = application
        self.options: dict[str, Any] = {
            "bind": bind,
            "workers": 1,
            "worker_class": "gthread",
            "threads": threads,
            "timeout": timeout,
            "graceful_timeout": 15,
            "keepalive": 2,
            "backlog": 64,
            "limit_request_line": 2048,
            "limit_request_fields": 40,
            "limit_request_field_size": 4096,
            "umask": 0o177,
            "accesslog": "-",
            "errorlog": "-",
            "capture_output": True,
        }
        if certfile is not None and keyfile is not None:
            self.options.update({"certfile": str(certfile), "keyfile": str(keyfile)})
        super().__init__()

    def load_config(self) -> None:
        for key, value in self.options.items():
            if key in self.cfg.settings and value is not None:
                self.cfg.set(key.lower(), value)

    def load(self) -> RelayApplication:
        return self.application
