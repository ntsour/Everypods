#!/usr/bin/env python3
"""Independent DIRCON receiver. Never generates or writes heart-rate samples."""
import argparse
import json
import socket
import struct
import time
import uuid


def bluetooth_uuid(short):
    return uuid.UUID(f"{short:08x}-0000-1000-8000-00805f9b34fb").bytes


def read_exact(peer, count):
    chunks = bytearray()
    while len(chunks) < count:
        part = peer.recv(count - len(chunks))
        if not part:
            raise EOFError("Sensor closed the connection (Stop, sensor gap, or network loss)")
        chunks.extend(part)
    return bytes(chunks)


def receive(peer):
    version, kind, sequence, code, length = struct.unpack("!BBBBH", read_exact(peer, 6))
    if version != 1 or length > 4096:
        raise ValueError("Invalid DIRCON header")
    return kind, sequence, code, read_exact(peer, length)


def request(peer, kind, sequence, payload=b""):
    peer.sendall(struct.pack("!BBBBH", 1, kind, sequence, 0, len(payload)) + payload)
    reply_kind, reply_sequence, code, body = receive(peer)
    if (reply_kind, reply_sequence, code) != (kind, sequence, 0):
        raise ValueError(f"Unexpected reply: {reply_kind=}, {reply_sequence=}, {code=}")
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="Wi-Fi IP shown in EveryPods diagnostics")
    parser.add_argument("--port", type=int, default=36866)
    parser.add_argument("--seconds", type=int, default=30)
    args = parser.parse_args()
    with socket.create_connection((args.host, args.port), timeout=5) as peer:
        peer.settimeout(5)
        services = request(peer, 1, 1)
        if len(services) % 16 or bluetooth_uuid(0x180D) not in [services[i:i+16] for i in range(0, len(services), 16)]:
            raise ValueError("Heart Rate Service not advertised by TCP discovery")
        chars = request(peer, 2, 2, bluetooth_uuid(0x180D))
        if chars[:16] != bluetooth_uuid(0x180D) or (len(chars) - 16) % 17:
            raise ValueError("Malformed characteristic discovery")
        if not any(chars[i:i+16] == bluetooth_uuid(0x2A37) and chars[i+16] & 4 for i in range(16, len(chars), 17)):
            raise ValueError("Heart Rate Measurement is not notifiable")
        ack = request(peer, 5, 3, bluetooth_uuid(0x2A37) + b"\x01")
        if ack != bluetooth_uuid(0x2A37):
            raise ValueError("Unexpected subscription acknowledgement")
        print(json.dumps({"stage": "subscribed", "note": "TCP verified; this does not test mDNS or Zwift discovery"}), flush=True)
        deadline = time.monotonic() + args.seconds
        samples = 0
        while time.monotonic() < deadline:
            peer.settimeout(min(5, max(0.01, deadline - time.monotonic())))
            try:
                kind, _, code, body = receive(peer)
            except socket.timeout:
                if time.monotonic() >= deadline:
                    break
                # A timeout after a partial frame must not restart frame parsing.
                raise TimeoutError("No complete frame within receive timeout; verify live AirPods samples")
            if kind != 6 or code != 0 or body[:16] != bluetooth_uuid(0x2A37) or len(body) < 18:
                raise ValueError("Unexpected heart-rate notification")
            flags = body[16]
            if flags & 1 and len(body) < 19:
                raise ValueError("Truncated 16-bit BPM")
            bpm = int.from_bytes(body[17:19], "little") if flags & 1 else body[17]
            if not 1 <= bpm <= 255:
                raise ValueError(f"Invalid BPM: {bpm}")
            samples += 1
            print(json.dumps({"stage": "live", "bpm": bpm, "received_unix": round(time.time(), 3)}), flush=True)
        print(json.dumps({"stage": "complete", "samples": samples}), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, EOFError, ValueError) as error:
        raise SystemExit(str(error))
