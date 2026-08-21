"""Cross-language Java-to-Python protocol test."""

import json
from pathlib import Path
import subprocess
import time

from android_imessage_relay.crypto import MessageCrypto, verify_request


def test_java_encryption_and_signature_are_python_compatible(tmp_path):
    source = Path(__file__).parent / "interop/InteropProducer.java"
    subprocess.run(["javac", "-d", str(tmp_path), str(source)], check=True, timeout=30)
    crypto = MessageCrypto(tmp_path)
    result = subprocess.run(
        ["java", "-cp", str(tmp_path), "InteropProducer", crypto.public_key_base64, str(int(time.time()))],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    device_public_key, body_text, signature = result.stdout.splitlines()
    body = body_text.encode("utf-8")
    verify_request(device_public_key, "POST", "/v1/messages", body, signature)
    assert crypto.decrypt(json.loads(body)) == {
        "sender": "Example sender",
        "body": "Code 123456",
    }
