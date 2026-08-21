"""Local administration and service entry point."""

from __future__ import annotations

import argparse
import ipaddress
from pathlib import Path
import subprocess
import sys

from .config import RelayConfig
from .server import RelayServer, build_application
from .store import RelayStore


DEFAULT_ROOT = Path.home() / "Library/Application Support/AndroidIMessageRelay"


def _validate_loopback_bind(value: str) -> str:
    try:
        if value.startswith("["):
            host, separator, port_text = value[1:].partition("]:")
        else:
            host, separator, port_text = value.rpartition(":")
        address = ipaddress.ip_address(host)
        port = int(port_text)
    except ValueError as error:
        raise SystemExit("TCP address must be a loopback IP and port") from error
    if not separator or not address.is_loopback or not 1 <= port <= 65535:
        raise SystemExit("TCP mode is restricted to a loopback IP address")
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="android-imessage-relay")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_ROOT / "data")
    commands = parser.add_subparsers(dest="command", required=True)

    serve = commands.add_parser("serve")
    endpoint = serve.add_mutually_exclusive_group()
    endpoint.add_argument("--socket", type=Path, default=DEFAULT_ROOT / "run/relay.sock")
    endpoint.add_argument("--listen", help="TCP address in HOST:PORT form")
    serve.add_argument("--tls-cert", type=Path)
    serve.add_argument("--tls-key", type=Path)
    serve.add_argument("--messages-script", type=Path, required=True)
    serve.add_argument("--recipient-helper", type=Path, required=True)
    serve.add_argument("--threads", type=int, default=4)
    serve.add_argument("--timeout", type=int, default=30)
    serve.add_argument("--dry-run", action="store_true")
    serve.add_argument("--allow-missing-access-header", action="store_true")

    approve = commands.add_parser("approve")
    approve.add_argument("request_id")
    approve.add_argument("--replace", action="store_true")
    reject = commands.add_parser("reject")
    reject.add_argument("request_id")
    commands.add_parser("pending")
    recipient = commands.add_parser("set-recipient")
    recipient.add_argument("--recipient-helper", type=Path, required=True)
    return parser


def _config(arguments: argparse.Namespace) -> RelayConfig:
    if arguments.listen:
        _validate_loopback_bind(arguments.listen)
        if (arguments.tls_cert is None) != (arguments.tls_key is None):
            raise SystemExit("Both --tls-cert and --tls-key are required for TLS")
        if arguments.tls_cert is None:
            raise SystemExit("TCP mode requires --tls-cert and --tls-key")
        bind = arguments.listen
        socket_path = DEFAULT_ROOT / "run/unused.sock"
    else:
        bind = f"unix:{arguments.socket}"
        socket_path = arguments.socket
    if not 1 <= arguments.threads <= 16 or not 5 <= arguments.timeout <= 120:
        raise SystemExit("Thread or timeout setting is outside the supported range")
    config = RelayConfig(
        data_dir=arguments.data_dir,
        socket_path=socket_path,
        messages_script=arguments.messages_script,
        recipient_helper=arguments.recipient_helper,
        require_access_header=not arguments.allow_missing_access_header,
        dry_run=arguments.dry_run,
        threads=arguments.threads,
        channel_timeout=arguments.timeout,
    )
    arguments.bind = bind
    return config


def main() -> None:
    arguments = _parser().parse_args()
    if arguments.command == "serve":
        config = _config(arguments)
        RelayServer(
            build_application(config),
            arguments.bind,
            config.threads,
            config.channel_timeout,
            arguments.tls_cert,
            arguments.tls_key,
        ).run()
        return
    if arguments.command == "set-recipient":
        recipient = sys.stdin.buffer.read(1024)
        if len(recipient) >= 1024:
            raise SystemExit("Recipient input is too large")
        subprocess.run(
            [str(arguments.recipient_helper), "set"],
            input=recipient,
            check=True,
            timeout=10,
        )
        return
    store = RelayStore(arguments.data_dir / "relay.sqlite3")
    if arguments.command == "pending":
        for request in store.pending_pairings():
            print(f"{request.display_code} pairingRequests/{request.request_id}")
        return
    request_id = store.resolve_pairing_id(arguments.request_id)
    request = store.approve_pairing(request_id, replace=arguments.replace) \
        if arguments.command == "approve" else store.reject_pairing(request_id)
    print(f"name=pairingRequests/{request.request_id}")
    print(f"state={request.state}")
    print(f"display_code={request.display_code}")


if __name__ == "__main__":
    main()
