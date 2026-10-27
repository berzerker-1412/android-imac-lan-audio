#!/usr/bin/env python3
"""Exercise the control protocol on loopback without opening production ports."""

import json
import os
import signal
import secrets
import socket
import subprocess
import sys
import time


PORT = 50556
STATUS_DIR = os.path.expanduser(
    "~/Library/Application Support/OpenBot/LAN Audio Receiver"
)


def read_line(connection: socket.socket, maximum: int = 4096) -> bytes:
    result = bytearray()
    while len(result) <= maximum:
        chunk = connection.recv(1)
        if not chunk:
            break
        if chunk == b"\n":
            return bytes(result)
        result.extend(chunk)
    raise RuntimeError("control response missing or too long")


def authenticated_request(pin: str, request: dict) -> dict:
    with socket.create_connection(("127.0.0.1", PORT), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(f"LANCTRL1\n{pin}\n".encode("ascii"))
        if read_line(connection, 16) != b"OK":
            raise RuntimeError("control PIN rejected")
        connection.sendall(json.dumps(request, separators=(",", ":")).encode() + b"\n")
        return json.loads(read_line(connection).decode())


def failed_auth(pin: str) -> bytes:
    with socket.create_connection(("127.0.0.1", PORT), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(f"LANCTRL1\n{pin}\n".encode("ascii"))
        return read_line(connection, 16)


def register_device(pin: str, device_id: str, name: str, token: str) -> bytes:
    with socket.create_connection(("127.0.0.1", PORT), timeout=5) as connection:
        connection.settimeout(5)
        fields = ("LANPAIR1", pin, device_id, name, token)
        connection.sendall(("\n".join(fields) + "\n").encode("utf-8"))
        return read_line(connection, 16)


def paired_request(device_id: str, token: str, request: dict) -> dict:
    with socket.create_connection(("127.0.0.1", PORT), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(f"LANCTRL2\n{device_id}\n{token}\n".encode("ascii"))
        if read_line(connection, 16) != b"OK":
            raise RuntimeError("paired-device authentication failed")
        connection.sendall(json.dumps(request, separators=(",", ":")).encode() + b"\n")
        return json.loads(read_line(connection).decode())


def rejected_paired_auth(device_id: str, token: str) -> bytes:
    with socket.create_connection(("127.0.0.1", PORT), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(f"LANCTRL2\n{device_id}\n{token}\n".encode("ascii"))
        return read_line(connection, 16)


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} /path/to/android-imac-audio-receiver", file=sys.stderr)
        return 2

    process = subprocess.Popen(
        [sys.argv[1], "--test-control-server"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    status_path = os.path.join(STATUS_DIR, f"control-test-{process.pid}.json")
    transcript: list[str] = []
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and not os.path.exists(status_path):
            if process.poll() is not None:
                output = process.stdout.read() if process.stdout else ""
                raise RuntimeError(f"test listener exited with {process.returncode}: {output.strip()}")
            time.sleep(0.05)
        with open(status_path, encoding="utf-8") as status_file:
            private_status = json.load(status_file)
        pin = private_status["pin"]
        if private_status.get("state") != "ready" or private_status.get("ip") != "127.0.0.1":
            raise RuntimeError("test listener private status was invalid")
        transcript.append("Loopback-only control listener startup: PASS")

        bad_pin = ("1" if pin[0] == "0" else "0") + pin[1:]
        if failed_auth(bad_pin) != b"ERR":
            raise RuntimeError("incorrect PIN was not rejected")
        transcript.append("PIN rejection and connection close: PASS")

        volume_response = authenticated_request(pin, {"id": 1, "cmd": "get_volume"})
        if volume_response.get("id") != 1 or volume_response.get("ok") is not True:
            raise RuntimeError("CoreAudio get_volume failed: " + str(volume_response.get("error", "invalid response")))
        volume = volume_response.get("volume")
        if not isinstance(volume, int) or not 0 <= volume <= 100:
            raise RuntimeError("CoreAudio returned an invalid volume")
        set_response = authenticated_request(pin, {"id": 2, "cmd": "set_volume", "value": volume})
        if set_response.get("id") != 2 or set_response.get("ok") is not True:
            raise RuntimeError("CoreAudio set_volume failed: " + str(set_response.get("error", "invalid response")))
        transcript.append("Control JSON + CoreAudio get/set same rounded volume: PASS")

        device_id = secrets.token_hex(16)
        device_token = secrets.token_hex(32)
        if register_device(pin, device_id, "Test Android", device_token) != b"OK":
            raise RuntimeError("device pairing registration failed")
        paired_volume = paired_request(device_id, device_token, {"id": 10, "cmd": "get_volume"})
        if paired_volume.get("id") != 10 or paired_volume.get("ok") is not True:
            raise RuntimeError("persistent paired control request failed")
        transcript.append("LANPAIR1 registration and LANCTRL2 auth: PASS (token redacted)")

        invalid_response = authenticated_request(pin, {"id": 3, "cmd": "set_volume", "value": 101})
        if invalid_response.get("id") != 3 or invalid_response.get("ok") is not False:
            raise RuntimeError("out-of-range volume was not rejected")
        transcript.append("Volume range validation: PASS")

        for _ in range(4):
            if failed_auth(bad_pin) != b"ERR":
                raise RuntimeError("incorrect PIN was not rejected during retry test")
        if failed_auth(pin) != b"ERR":
            raise RuntimeError("PIN retry rate limit was not enforced")
        transcript.append("Failed-PIN lock until test receiver restart: PASS (credential redacted)")

        if paired_request(device_id, device_token, {"id": 11, "cmd": "get_volume"}).get("ok") is not True:
            raise RuntimeError("PIN lock incorrectly blocked an already paired device")
        if paired_request(device_id, device_token, {"id": 12, "cmd": "unpair"}).get("ok") is not True:
            raise RuntimeError("device unpair was not acknowledged")
        if rejected_paired_auth(device_id, device_token) != b"ERR":
            raise RuntimeError("unpaired token remained authorized")
        transcript.append("Paired access survives PIN lock; unpair revokes it: PASS (token redacted)")

        process.send_signal(signal.SIGTERM)
        process.wait(timeout=5)
        if process.returncode != 0:
            raise RuntimeError("loopback test listener did not stop cleanly")
        if os.path.exists(status_path):
            raise RuntimeError("private control test status was not removed")
        transcript.append("Private test status cleanup: PASS")
        print("\n".join(transcript))
        return 0
    except Exception as error:
        if process.poll() is None:
            process.send_signal(signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        print(f"Control protocol test: FAIL ({type(error).__name__}: {error})", file=sys.stderr)
        return 1
    finally:
        if process.stdout is not None:
            process.stdout.close()


if __name__ == "__main__":
    raise SystemExit(main())
