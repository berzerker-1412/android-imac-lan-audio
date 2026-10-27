#!/usr/bin/env python3
"""Run the receiver and send a PIN-paired synthetic PCM stream locally."""

import math
import json
import os
import re
import secrets
import select
import signal
import socket
import struct
import subprocess
import sys
import time


PORT = 50555
CONTROL_PORT = 50556
SAMPLE_RATE = 48_000
FRAME_COUNT = SAMPLE_RATE // 4
IP_PATTERN = re.compile(r"Status: listening on ([0-9.]+):([0-9]+) via ")


class LineReader:
    def __init__(self, stream):
        self.stream = stream
        self.buffer = bytearray()

    def read(self, timeout: float, process: subprocess.Popen) -> str:
        while b"\n" not in self.buffer:
            ready, _, _ = select.select([self.stream], [], [], timeout)
            if not ready:
                raise TimeoutError("receiver did not produce expected status")
            chunk = os.read(self.stream.fileno(), 4096)
            if not chunk:
                raise RuntimeError(f"receiver exited with status {process.poll()}")
            self.buffer.extend(chunk)
        line, _, remainder = self.buffer.partition(b"\n")
        self.buffer = bytearray(remainder)
        return line.decode("utf-8").rstrip("\r")


def send_tone(connection: socket.socket) -> None:
    pcm = bytearray()
    for index in range(FRAME_COUNT):
        sample = int(900 * math.sin(2 * math.pi * 440 * index / SAMPLE_RATE))
        pcm.extend(struct.pack("<hh", sample, sample))
    chunk_sizes = (137, 509, 1021, 2047)
    offset = 0
    chunk_index = 0
    while offset < len(pcm):
        size = chunk_sizes[chunk_index % len(chunk_sizes)]
        connection.sendall(pcm[offset : offset + size])
        offset += size
        chunk_index += 1
        time.sleep(0.002)


def receive_line(connection: socket.socket, maximum: int = 4096) -> bytes:
    result = bytearray()
    while len(result) <= maximum:
        chunk = connection.recv(1)
        if not chunk:
            break
        if chunk == b"\n":
            return bytes(result)
        result.extend(chunk)
    raise RuntimeError("control response was missing or too long")


def control_request(address: str, pin: str, request: dict) -> dict:
    with socket.create_connection((address, CONTROL_PORT), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(f"LANCTRL1\n{pin}\n".encode("ascii"))
        if receive_line(connection, 16) != b"OK":
            raise RuntimeError("control listener rejected current pairing PIN")
        connection.sendall(json.dumps(request, separators=(",", ":")).encode("utf-8") + b"\n")
        return json.loads(receive_line(connection).decode("utf-8"))


def register_device(address: str, pin: str, device_id: str, name: str, token: str) -> None:
    with socket.create_connection((address, CONTROL_PORT), timeout=5) as connection:
        connection.settimeout(5)
        fields = ("LANPAIR1", pin, device_id, name, token)
        connection.sendall(("\n".join(fields) + "\n").encode("utf-8"))
        if receive_line(connection, 16) != b"OK":
            raise RuntimeError("receiver rejected test-device registration")


def paired_control_request(address: str, device_id: str, token: str, request: dict) -> dict:
    with socket.create_connection((address, CONTROL_PORT), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(f"LANCTRL2\n{device_id}\n{token}\n".encode("ascii"))
        if receive_line(connection, 16) != b"OK":
            raise RuntimeError("receiver rejected paired test device")
        connection.sendall(json.dumps(request, separators=(",", ":")).encode("utf-8") + b"\n")
        return json.loads(receive_line(connection).decode("utf-8"))


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} /path/to/android-imac-audio-receiver", file=sys.stderr)
        return 2

    process = subprocess.Popen(
        [sys.argv[1]],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=0,
    )
    reader = LineReader(process.stdout)
    pin = None
    address = None
    transcript: list[str] = []
    try:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            line = reader.read(max(0.1, deadline - time.monotonic()), process)
            transcript.append(line)
            ip_match = IP_PATTERN.search(line)
            if ip_match:
                address = ip_match.group(1)
            if line.startswith("Bonjour ready:"):
                break
        status_path = os.path.expanduser(
            "~/Library/Application Support/OpenBot/LAN Audio Receiver/status.json"
        )
        if os.path.exists(status_path):
            with open(status_path, encoding="utf-8") as status_file:
                private_status = json.load(status_file)
            pin = private_status.get("pin")
            address = private_status.get("ip", address)
        if pin is None or address is None:
            raise RuntimeError("receiver startup status was incomplete")

        qr_test = subprocess.run(
            [sys.argv[1], "--test-qr"], capture_output=True, text=True, timeout=20
        )
        if qr_test.returncode != 0:
            raise RuntimeError("QR generation self-test failed")
        transcript.append(qr_test.stdout.strip())

        bad_pin = ("1" if pin[0] == "0" else "0") + pin[1:]
        with socket.create_connection((address, CONTROL_PORT), timeout=5) as connection:
            connection.settimeout(5)
            connection.sendall(f"LANCTRL1\n{bad_pin}\n".encode("ascii"))
            if receive_line(connection, 16) != b"ERR":
                raise RuntimeError("control listener accepted an invalid PIN")
        transcript.append("Control PIN rejection test: PASS (credential redacted)")

        volume_response = control_request(address, pin, {"id": 1, "cmd": "get_volume"})
        if volume_response.get("id") != 1 or volume_response.get("ok") is not True:
            raise RuntimeError("CoreAudio get_volume failed: " + str(volume_response.get("error", "invalid response")))
        volume = volume_response.get("volume")
        if not isinstance(volume, int) or not 0 <= volume <= 100:
            raise RuntimeError("CoreAudio returned an invalid volume")
        set_response = control_request(
            address, pin, {"id": 2, "cmd": "set_volume", "value": volume}
        )
        if set_response.get("id") != 2 or set_response.get("ok") is not True:
            raise RuntimeError("CoreAudio set_volume failed: " + str(set_response.get("error", "invalid response")))
        transcript.append("Control protocol/CoreAudio get_volume + set_volume(same rounded value): PASS")

        invalid_response = control_request(
            address, pin, {"id": 3, "cmd": "set_volume", "value": 101}
        )
        if invalid_response.get("id") != 3 or invalid_response.get("ok") is not False:
            raise RuntimeError("control listener did not reject an out-of-range volume")
        transcript.append("Control range validation: PASS")

        device_id = secrets.token_hex(16)
        device_token = secrets.token_hex(32)
        register_device(address, pin, device_id, "Integration Test", device_token)
        if paired_control_request(address, device_id, device_token,
                                  {"id": 10, "cmd": "get_volume"}).get("ok") is not True:
            raise RuntimeError("paired control did not return volume")
        transcript.append("Persistent-device control authentication: PASS (token redacted)")

        with socket.create_connection((address, PORT), timeout=5) as connection:
            connection.settimeout(5)
            connection.sendall(f"LANPCM1\n{pin}\n".encode("ascii"))
            response = bytearray()
            while not response.endswith(b"\n") and len(response) < 4:
                chunk = connection.recv(1)
                if not chunk:
                    raise RuntimeError("receiver closed before pairing response")
                response.extend(chunk)
            if response != b"OK\n":
                raise RuntimeError("receiver rejected generated PIN")
            send_tone(connection)
            connection.shutdown(socket.SHUT_WR)

        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            line = reader.read(max(0.1, deadline - time.monotonic()), process)
            transcript.append(line)
            if line.startswith("Sender disconnected;"):
                break
        summary = next((line for line in transcript if line.startswith("Sender disconnected;")), None)
        if summary is None:
            raise RuntimeError("receiver did not confirm stream completion")
        rendered = re.search(r"rendered from queue: ([0-9]+)", summary)
        if rendered is None or int(rendered.group(1)) == 0:
            raise RuntimeError("AVAudioEngine render callback did not consume queued PCM")
        transcript.insert(0, "Legacy LANPCM1 compatibility: PASS")

        with socket.create_connection((address, PORT), timeout=5) as connection:
            connection.settimeout(5)
            connection.sendall(f"LANPCM2\n{device_id}\n{device_token}\n".encode("ascii"))
            if receive_line(connection, 16) != b"OK":
                raise RuntimeError("receiver rejected paired synthetic PCM stream")
            send_tone(connection)
            connection.shutdown(socket.SHUT_WR)
        deadline = time.monotonic() + 10
        paired_summary = None
        while time.monotonic() < deadline:
            line = reader.read(max(0.1, deadline - time.monotonic()), process)
            transcript.append(line)
            if line.startswith("Sender disconnected;"):
                paired_summary = line
                break
        if paired_summary is None or "received 12000 frames" not in paired_summary:
            raise RuntimeError("receiver did not finish paired PCM stream")
        transcript.append("Persistent LANPCM2 synthetic PCM: PASS (12000 frames)")

        if paired_control_request(address, device_id, device_token,
                                  {"id": 11, "cmd": "unpair"}).get("ok") is not True:
            raise RuntimeError("receiver did not acknowledge paired-device unpair")
        transcript.append("Receiver-side unpair revocation: PASS (token redacted)")

        process.send_signal(signal.SIGTERM)
        process.wait(timeout=5)
        transcript.append(f"Receiver shutdown exit={process.returncode}")
        if process.returncode != 0:
            raise RuntimeError("receiver did not shut down cleanly")
        transcript.insert(0, f"Synthetic PCM test: PASS ({FRAME_COUNT} stereo frames, 440 Hz, 250 ms)")
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
        print(f"Synthetic PCM test: FAIL ({type(error).__name__}: {error})", file=sys.stderr)
        for line in transcript:
            print(line, file=sys.stderr)
        return 1
    finally:
        if process.stdout is not None:
            process.stdout.close()


if __name__ == "__main__":
    raise SystemExit(main())
