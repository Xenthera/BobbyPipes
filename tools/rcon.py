"""Minimal RCON client for driving the dev server headlessly.

Gradle does not forward stdin to runServer, so this is how commands get sent
during verification. Enable RCON in run/server.properties first:

    enable-rcon=true
    rcon.password=bobbypipes
    rcon.port=25585

Then pipe commands in, one per line:

    ./gradlew runServer &
    python3 tools/rcon.py <<EOF
    setblock 0 100 0 bobbypipes:pipe
    bobbypipes network
    EOF
"""
import socket
import struct
import sys

HOST, PORT, PASSWORD = "127.0.0.1", 25585, "bobbypipes"

LOGIN = 3
COMMAND = 2


def pack(req_id, kind, body):
    payload = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def read_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("socket closed")
        buf += chunk
    return buf


def read_packet(sock):
    length = struct.unpack("<i", read_exact(sock, 4))[0]
    payload = read_exact(sock, length)
    req_id, kind = struct.unpack("<ii", payload[:8])
    return req_id, kind, payload[8:-2].decode("utf-8", "replace")


def main(commands):
    with socket.create_connection((HOST, PORT), timeout=15) as sock:
        sock.sendall(pack(1, LOGIN, PASSWORD))
        req_id, _, _ = read_packet(sock)
        if req_id == -1:
            print("RCON auth failed")
            return 1
        for i, command in enumerate(commands, start=2):
            sock.sendall(pack(i, COMMAND, command))
            _, _, body = read_packet(sock)
            body = body.strip()
            if body:
                print(f"{command}\n    -> {body}")
            else:
                print(command)
    return 0


if __name__ == "__main__":
    sys.exit(main([line for line in sys.stdin.read().splitlines() if line.strip()]))
