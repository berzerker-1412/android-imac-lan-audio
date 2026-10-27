#!/usr/bin/env python3
"""Send a short, quiet synthetic tone using the LANPCM1 v1 protocol."""

import argparse
import getpass
import math
import socket
import struct
import sys
import time


PORT = 50555
SAMPLE_RATE = 48_000
FRAME_COUNT = SAMPLE_RATE // 4  # 250 ms


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="iMac LAN IPv4 address")
    parser.add_argument("--port", type=int, default=PORT)
    args = parser.parse_args()

    pin = getpass.getpass("Pairing PIN (input hidden): ")
    if len(pin) != 6 or not pin.isdigit():
        print("PIN must be six digits", file=sys.stderr)
        return 2

    try:
        with socket.create_connection((args.host, args.port), timeout=5) as connection:
            connection.settimeout(5)
            connection.sendall(f"LANPCM1\n{pin}\n".encode("ascii"))
            response = bytearray()
            while not response.endswith(b"\n") and len(response) < 4:
                chunk = connection.recv(1)
                if not chunk:
                    raise RuntimeError("receiver closed before handshake response")
                response.extend(chunk)
            if response != b"OK\n":
                print("Pairing rejected", file=sys.stderr)
                return 1

            pcm = bytearray()
            for index in range(FRAME_COUNT):
                # Low-level test tone; same value in both channels.
                sample = int(900 * math.sin(2 * math.pi * 440 * index / SAMPLE_RATE))
                pcm.extend(struct.pack("<hh", sample, sample))

            # Deliberately vary chunk sizes to test frame reassembly across TCP reads.
            offsets = (137, 509, 1021, 2047)
            offset = 0
            chunk_index = 0
            while offset < len(pcm):
                size = offsets[chunk_index % len(offsets)]
                connection.sendall(pcm[offset : offset + size])
                offset += size
                chunk_index += 1
                time.sleep(0.002)
            connection.shutdown(socket.SHUT_WR)
        print(f"Synthetic PCM sent: {FRAME_COUNT} stereo frames, 440 Hz, 250 ms")
        return 0
    except (OSError, RuntimeError) as error:
        print(f"Synthetic send failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
