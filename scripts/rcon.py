#!/usr/bin/env python3
"""A minimal source-RCON client — stdlib only, no external dependency.

Used by live-server-test.sh to send a single command (normally 'stop') to the test server and
get its response, without pulling in mcrcon or any other package the sandbox this runs in cannot
be relied on to have.
"""
import socket
import struct
import sys


def _packet(sock: socket.socket, request_id: int, packet_type: int, payload: str) -> None:
    body = struct.pack("<ii", request_id, packet_type) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def _read_packet(sock: socket.socket) -> tuple[int, int, str]:
    length = struct.unpack("<i", sock.recv(4))[0]
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    request_id, packet_type = struct.unpack("<ii", data[:8])
    payload = data[8:-2].decode("utf-8", errors="replace")
    return request_id, packet_type, payload


def send(host: str, port: int, password: str, command: str, timeout: float = 15.0) -> str:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        _packet(sock, 1, 3, password)  # SERVERDATA_AUTH
        request_id, _, _ = _read_packet(sock)
        if request_id == -1:
            raise RuntimeError("RCON auth rejected — wrong password")
        _packet(sock, 2, 2, command)  # SERVERDATA_EXECCOMMAND
        _, _, payload = _read_packet(sock)
        return payload


if __name__ == "__main__":
    if len(sys.argv) != 5:
        print("usage: rcon.py <host> <port> <password> <command>", file=sys.stderr)
        sys.exit(2)
    host, port, password, command = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    try:
        print(send(host, port, password, command))
    except (ConnectionRefusedError, OSError, RuntimeError) as exc:
        print(f"rcon.py: {exc}", file=sys.stderr)
        sys.exit(1)
