"""Experimental legacy GoPro Wi-Fi Remote camera emulator (Python 3, stdlib).

Protocol references and operating instructions: gopro_wifi_probe.md.
Does not control VT, LoRa, or a drone. Live remote compatibility is untested.
"""
import argparse
import collections
import datetime
import json
from pathlib import Path
import socket
import sys
import time


class Camera:
    def __init__(self):
        self.mode = 0
        self.recording = 0
        self.power = 1
        self.version_seen = False
        self.recent = collections.OrderedDict()

    def receive(self, packet, peer):
        if len(packet) < 13 or packet[:8] != bytes(8):
            return None, {"valid": False, "reason": "unrecognised header"}
        command = packet[11:13].decode("ascii", errors="replace")
        args = packet[13:]
        sequence = int.from_bytes(packet[9:11], "big")
        info = {"valid": True, "command": command, "sequence": sequence,
                "subsystem": packet[8], "arguments": args.hex(" ")}
        # Replay the same response for retransmissions; do not repeat events.
        key = (peer, packet)
        now = time.monotonic()
        while self.recent and now - next(iter(self.recent.values()))[0] > 5:
            self.recent.popitem(last=False)
        if key in self.recent:
            info["duplicate"] = True
            return self.recent[key][1], info
        info["duplicate"] = False
        if command in ("SH", "CM", "PW") and not args:
            payload = b"\x01"  # Invalid: required parameter absent.
        elif command == "SH":
            self.recording = int(bool(args[0]))
            info["event"] = "SHUTTER_START" if self.recording else "SHUTTER_STOP"
            payload = bytes((0, self.recording))
        elif command == "CM":
            self.mode = args[0]
            payload = bytes((0, self.mode))
        elif command == "PW":
            self.power = int(bool(args[0]))
            payload = bytes((0, self.power))
        elif command == "cv":
            # Observed legacy negotiation: initial response, then identity.
            payload = (b"\x00\x02\x01\x0cHD3.03.03.00.Hero3-BlackEdition"
                       if self.version_seen else b"\x01")
            self.version_seen = True
        elif command == "st":
            payload = bytes((0, self.mode, self.recording, 0, self.recording))
        elif command == "pw":
            payload = bytes((0, self.power))
        elif command == "se":
            payload = bytes(31)
        elif command == "lc":
            payload = bytes(601)  # Success plus blank 64 x 75 monochrome LCD.
        elif command in ("OO", "wt"):
            payload = b"\x00"
        else:
            info["unsupported"] = True
            payload = b"\x01"
        reply = packet[:13] + payload
        self.recent[key] = (now, reply)
        if len(self.recent) > 512:
            self.recent.popitem(last=False)
        return reply, info


def self_test():
    camera = Camera()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server, \
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
        server.bind(("127.0.0.1", 0))
        server.settimeout(2)
        client.settimeout(2)
        packet = bytes(8) + b"\x00\x00\x01SH\x01"
        client.sendto(packet, server.getsockname())
        received, peer = server.recvfrom(2048)
        reply, info = camera.receive(received, peer)
        assert info["event"] == "SHUTTER_START"
        server.sendto(reply, peer)
        assert client.recvfrom(2048)[0] == packet[:13] + b"\x00\x01"
        repeated, info = camera.receive(packet, peer)
        assert repeated == reply and info["duplicate"] and "event" not in info
        assert camera.receive(b"short", peer)[0] is None
        unknown = bytes(8) + b"\x00\x00\x02ZZ"
        assert camera.receive(unknown, peer)[0][-1:] == b"\x01"
    print("PASS: loopback UDP, shutter decode, responses, duplicates, malformed/unknown packets.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8484)
    parser.add_argument("--listen-only", action="store_true", help="Log without replying")
    parser.add_argument("--log", type=Path, default=Path(__file__).with_name("gopro-capture.jsonl"))
    parser.add_argument("--self-test", action="store_true")
    opts = parser.parse_args()
    if opts.self_test:
        self_test()
        return
    camera = Camera()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind((opts.bind, opts.port))
        sock.settimeout(1)
        with opts.log.open("a", encoding="utf-8") as log:
            print(f"Listening on {opts.bind}:{opts.port}; log: {opts.log.resolve()}", flush=True)
            print("Join the remote's HERO-RC Wi-Fi first. Ctrl+C stops. No VT/drone commands.", flush=True)
            last_received = time.monotonic()
            try:
                while True:
                    try:
                        packet, peer = sock.recvfrom(65535)
                    except socket.timeout:
                        if time.monotonic() - last_received > 20:
                            print("No packets: check remote pairing mode, Wi-Fi connection, IPv4 and firewall.", flush=True)
                            last_received = time.monotonic()
                        continue
                    last_received = time.monotonic()
                    reply, info = camera.receive(packet, peer)
                    info.update(time=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                peer=f"{peer[0]}:{peer[1]}", rx=packet.hex(" "))
                    if reply is not None and not opts.listen_only:
                        try:
                            sock.sendto(reply, peer)
                            info["tx"] = reply.hex(" ")
                        except OSError as exc:
                            info["send_error"] = str(exc)
                    line = json.dumps(info)
                    log.write(line + "\n")
                    log.flush()
                    print(line, flush=True)
            except KeyboardInterrupt:
                print("Stopped; capture saved.")


if __name__ == "__main__":
    try:
        main()
    except OSError as exc:
        print(f"Probe failed: {exc}", file=sys.stderr)
        sys.exit(1)
