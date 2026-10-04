#!/usr/bin/env python3
"""DPDK pcap-PMD integration checks. No physical ports or privilege required.

Tests codec dispatch, packet I/O, timeout/wrong-result failures, and CSV output.
Replay latency is not a performance result. Run with an unused output directory.
"""
import argparse
import csv
import json
import os
from pathlib import Path
import struct
import subprocess
import time


def packet(result, xid=1, port=9999):
    rpc = struct.pack('!8I', xid, 1, 0, 0, 0, 0, xid, result)
    udp = struct.pack('!4H', port, 49152, 8 + len(rpc), 0) + rpc
    ip = bytearray(struct.pack('!BBHHHBBH4s4s', 0x45, 0, 20+len(udp), 0,
                              0x4000, 64, 17, 0, bytes([192,0,2,2]), bytes([192,0,2,1])))
    total = sum(struct.unpack('!10H', ip))
    while total >> 16:
        total = (total & 65535) + (total >> 16)
    struct.pack_into('!H', ip, 10, (~total) & 65535)
    return bytes.fromhex('0200000000010200000000020800') + ip + udp


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    core = min(os.sched_getaffinity(0))
    for name, codec, frames, expected, mode in [
        ('add', 'add', [packet(18)], 0, None),
        ('mul', 'mul', [packet(77)], 0, None),
        ('start-barrier', 'add', [packet(18)], 0, None),
        ('wrong-result', 'add', [packet(19)], 1, None),
        ('stale-xid', 'add', [packet(18, 42)], 1, None),
        ('timeout', 'add', [], 1, None),
        ('flush-add', 'add', [packet(77,0x80000000+i,12346) for i in range(16)]+[packet(18)], 0, 'flush:1:1'),
        ('flush-unrelated', 'add', [packet(18,99,9998)]+[packet(77,0x80000000+i,12346) for i in range(16)]+[packet(18)], 0, 'flush:1:1'),
        ('flush-duplicate', 'add', [packet(77,0x80000000,12346)]*16, 1, 'flush:1:1'),
        ('flush-timeout', 'add', [], 1, 'flush:1:1'),
    ]:
        rx, tx, out = [args.output / (name+suffix) for suffix in ('.rx.pcap', '.tx.pcap', '.csv')]
        with rx.open('wb') as f:
            f.write(struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1))
            for frame in frames:
                f.write(struct.pack('<IIII', 0, 0, len(frame), len(frame)) + frame)
        cmd = [str(args.binary.resolve()), '-l', str(core), '--no-huge', '--no-pci',
               '--no-shconf', '--no-telemetry', '--file-prefix=lh-replay-'+name,
               '--vdev', f'net_pcap0,rx_pcap={rx.resolve()},tx_pcap={tx.resolve()}', '--',
               '192.0.2.1', '192.0.2.2', '02:00:00:00:00:02', '9999',
               '1', '0', '10000', codec, str(out.resolve())]
        if mode: cmd.append(mode)
        with (args.output / (name+'.log')).open('w') as log:
            if name == 'start-barrier':
                ready = (args.output/'barrier-ready').resolve()
                start = (args.output/'barrier-start').resolve()
                env = dict(os.environ, LH_BENCH_READY_FILE=str(ready), LH_BENCH_START_FILE=str(start))
                proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=env)
                try:
                    deadline = time.monotonic()+20
                    while not ready.exists() and proc.poll() is None and time.monotonic()<deadline:
                        time.sleep(.01)
                    if not ready.exists() or proc.poll() is not None or (out.exists() and out.stat().st_size):
                        raise RuntimeError('client failed to wait at start barrier')
                    start.touch()
                    proc.wait(timeout=30)
                finally:
                    if proc.poll() is None:
                        proc.kill(); proc.wait()
            else:
                proc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, timeout=30)
        rows = []
        if out.exists():
            with out.open() as f:
                rows = list(csv.DictReader(f))
        expected_rows = 0 if mode and expected else 1
        passed = proc.returncode == expected and len(rows) == expected_rows
        if rows: passed = passed and int(rows[0]['ok']) == (expected == 0)
        if mode:
            with Path(str(out)+'.flush.csv').open() as f:
                preparation = list(csv.DictReader(f))
            passed = passed and len(preparation)==1 and int(preparation[0]['ok'])==(expected==0)
            if expected==0: passed = passed and int(preparation[0]['received'])==16
        if passed and expected == 0:
            stamps = [int(rows[0][key]) for key in (
                'client_call_enter_ns', 'client_tx_ready_ns', 'client_tx_submit_ns',
                'client_rx_burst_ns', 'client_call_exit_ns')]
            passed = stamps == sorted(stamps)
        results.append(dict(case=name, returncode=proc.returncode, passed=passed))
    (args.output / 'summary.json').write_text(json.dumps(results, indent=2)+'\n')
    print(json.dumps(results))
    return 0 if all(row['passed'] for row in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
