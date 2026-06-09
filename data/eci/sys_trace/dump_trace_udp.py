#!/usr/bin/env python3
"""Dump Lauberhorn trace DDR through the lightweight UDP trace responder."""

from __future__ import annotations

import argparse
import os
import socket
import struct
import sys
import time
from dataclasses import dataclass
from pathlib import Path


REQ_MAGIC = b"LHTR"
RSP_MAGIC = b"LHTD"
VERSION = 1
OP_READ = 1
STATUS_OK = 0
TRACE_SLOT_BYTES = 64
MAX_CHUNK_BYTES = 1440
DEFAULT_BIND_IP = "129.132.102.8"
DEFAULT_BIND_PORT = 55555


@dataclass
class TraceStatus:
    write_slot: int
    wrapped: bool
    sample_lost: bool
    dma_error: bool


@dataclass
class Response:
    seq: int
    offset: int
    status: int
    read_status_error: bool
    trace_status: TraceStatus
    data: bytes

def build_request(
    seq: int,
    offset: int,
    length: int,
) -> bytes:
    return struct.pack("!4sBBBBIQI", REQ_MAGIC, VERSION, OP_READ, 0, 0, seq, offset, length)


def parse_response(payload: bytes) -> Response | None:
    if len(payload) < 32:
        return None
    if payload[:4] != RSP_MAGIC or payload[4] != VERSION:
        return None
    status = payload[5]
    flags = payload[6]
    seq = struct.unpack_from("!I", payload, 8)[0]
    offset = struct.unpack_from("!Q", payload, 12)[0]
    data_len = struct.unpack_from("!H", payload, 20)[0]
    write_slot = struct.unpack_from("!I", payload, 22)[0]
    status_bits = payload[26]
    data_start = 32
    data_end = data_start + data_len
    if len(payload) < data_end:
        return None
    return Response(
        seq=seq,
        offset=offset,
        status=status,
        read_status_error=bool(flags & 1),
        trace_status=TraceStatus(
            write_slot=write_slot,
            wrapped=bool(status_bits & 0x04),
            sample_lost=bool(status_bits & 0x02),
            dma_error=bool(status_bits & 0x01),
        ),
        data=payload[data_start:data_end],
    )


def recv_response(sock: socket.socket, seq: int, timeout: float) -> Response:
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"timed out waiting for response seq={seq}")
        sock.settimeout(remaining)
        payload = sock.recv(10000)
        response = parse_response(payload)
        if response is not None and response.seq == seq:
            return response


def wait_for_fpga(sock: socket.socket, timeout: float | None) -> tuple[Response, tuple[str, int]]:
    deadline = None if timeout is None else time.monotonic() + timeout
    while True:
        if deadline is None:
            sock.settimeout(None)
        else:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("timed out waiting for FPGA dump-over-network metadata")
            sock.settimeout(remaining)
        payload, addr = sock.recvfrom(10000)
        response = parse_response(payload)
        if response is not None and response.seq == 0 and response.status == STATUS_OK and not response.data:
            return response, addr


def request_chunk(
    sock: socket.socket,
    payload: bytes,
    seq: int,
    timeout: float,
    retries: int,
) -> Response:
    last_error: Exception | None = None
    for _ in range(retries + 1):
        sock.send(payload)
        try:
            return recv_response(sock, seq, timeout)
        except TimeoutError as exc:
            last_error = exc
    assert last_error is not None
    raise last_error


def aligned(value: int, name: str) -> int:
    if value % TRACE_SLOT_BYTES != 0:
        raise argparse.ArgumentTypeError(f"{name} must be {TRACE_SLOT_BYTES}-byte aligned")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-o", "--out", type=Path, help="output raw trace dump")
    parser.add_argument("--offset", type=lambda v: aligned(int(v, 0), "offset"), default=0)
    parser.add_argument("--bytes", type=lambda v: aligned(int(v, 0), "bytes"))
    parser.add_argument("--chunk-bytes", type=lambda v: aligned(int(v, 0), "chunk-bytes"), default=MAX_CHUNK_BYTES)
    parser.add_argument("--bind-ip", default=DEFAULT_BIND_IP, help="local IP address for the dump server")
    parser.add_argument("--port", type=int, default=DEFAULT_BIND_PORT, help="local UDP port for the dump server")
    parser.add_argument("--wait-timeout", type=float, help="timeout while waiting for FPGA metadata")
    parser.add_argument("--timeout", type=float, default=1.0)
    parser.add_argument("--retries", type=int, default=5)
    parser.add_argument("--status", action="store_true", help="query status only")
    args = parser.parse_args()

    if args.chunk_bytes <= 0 or args.chunk_bytes > MAX_CHUNK_BYTES:
        parser.error(f"--chunk-bytes must be in [64, {MAX_CHUNK_BYTES}]")
    if not args.status and args.out is None:
        parser.error("--out is required unless --status is used")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.bind_ip, args.port))

    print(f"waiting for FPGA metadata on {args.bind_ip}:{args.port}", file=sys.stderr)
    status_response, peer = wait_for_fpga(sock, args.wait_timeout)
    sock.connect(peer)
    print(f"accepted FPGA peer {peer[0]}:{peer[1]}", file=sys.stderr)

    seq = os.getpid() & 0xFFFFFFFF

    def send_read(offset: int, length: int) -> Response:
        nonlocal seq
        seq = (seq + 1) & 0xFFFFFFFF
        payload = build_request(seq, offset, length)
        response = request_chunk(sock, payload, seq, args.timeout, args.retries)
        if response.status != STATUS_OK:
            raise RuntimeError(f"trace responder returned status {response.status} at offset 0x{offset:x}")
        if response.offset != offset:
            raise RuntimeError(f"response offset mismatch: got 0x{response.offset:x}, expected 0x{offset:x}")
        if len(response.data) != length:
            raise RuntimeError(f"response length mismatch: got {len(response.data)}, expected {length}")
        return response

    ts = status_response.trace_status
    print(
        f"trace status: writeSlot={ts.write_slot} wrapped={int(ts.wrapped)} "
        f"sampleLost={int(ts.sample_lost)} dmaError={int(ts.dma_error)}"
    )
    if args.status:
        return 0

    total = args.bytes
    if total is None:
        if ts.wrapped:
            raise RuntimeError("--bytes is required when the trace buffer has wrapped")
        total = ts.write_slot * TRACE_SLOT_BYTES

    written = 0
    start = time.monotonic()
    with args.out.open("wb") as out:
        while written < total:
            length = min(args.chunk_bytes, total - written)
            response = send_read(args.offset + written, length)
            out.write(response.data)
            written += length
            if written % (64 * 1024 * 1024) == 0 or written == total:
                elapsed = max(time.monotonic() - start, 1e-9)
                mib_s = written / (1024 * 1024) / elapsed
                print(f"{written}/{total} bytes ({mib_s:.1f} MiB/s)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
