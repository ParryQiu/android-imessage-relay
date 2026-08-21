"""Runtime configuration."""

from dataclasses import dataclass
import os
from pathlib import Path


@dataclass(frozen=True)
class RelayConfig:
    """Validated local runtime settings."""

    data_dir: Path
    socket_path: Path
    messages_script: Path
    recipient_helper: Path
    require_access_header: bool = True
    dry_run: bool = False
    threads: int = 4
    channel_timeout: int = 30
    max_request_body: int = 64 * 1024
    pairing_ttl_seconds: int = 300
    lease_seconds: int = 60
    max_deliveries: int = 10_000
    max_pairing_requests: int = 1_000
    maintenance_interval_seconds: int = 3_600

    def prepare(self) -> None:
        if self.max_deliveries <= 0 or self.max_pairing_requests <= 0 \
                or self.maintenance_interval_seconds <= 0:
            raise RuntimeError("Relay capacity and maintenance settings must be positive")
        os.umask(0o077)
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.data_dir.chmod(0o700)
        self.socket_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.socket_path.parent.chmod(0o700)
