#!/usr/bin/env python3
"""Bounded startup probes, separate from compatibility and measured requests."""
import argparse
import json
import time
from pathlib import Path
from protocol import Client


def wait_ready(host, ports, timeout=30.0):
    if timeout <= 0 or not ports or len(set(ports)) != len(ports):
        raise ValueError('positive timeout and distinct ports required')
    start = time.monotonic()
    deadline = start + timeout
    attempts = []
    snapshots = {}
    while len(snapshots) != len(ports):
        for port in ports:
            if port in snapshots:
                continue
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return dict(passed=False, seconds=time.monotonic()-start,
                            attempts=attempts, stats=snapshots, error='readiness deadline')
            c = Client(host, port, timeout=min(0.25, remaining))
            try:
                snapshot = c.stats()
                # Malformed replies are failures, not transient startup errors.
                if 'pid' not in snapshot or 'version' not in snapshot:
                    raise ValueError('incomplete stats readiness response')
                snapshots[port] = snapshot
                attempts.append(dict(port=port, ready=True, elapsed=time.monotonic()-start))
            except (ConnectionRefusedError, TimeoutError) as exc:
                attempts.append(dict(port=port, ready=False, elapsed=time.monotonic()-start,
                                     error=type(exc).__name__))
            finally:
                c.close()
        if len(snapshots) != len(ports):
            time.sleep(min(0.05, max(0, deadline-time.monotonic())))
    return dict(passed=True, seconds=time.monotonic()-start, attempts=attempts, stats=snapshots)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host', required=True)
    p.add_argument('--ports', type=int, nargs='+', required=True)
    p.add_argument('--timeout', type=float, default=30)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.output.exists():
        p.error('output already exists')
    result = wait_ready(a.host, a.ports, a.timeout)
    a.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k != 'stats'}))
    raise SystemExit(0 if result['passed'] else 1)
