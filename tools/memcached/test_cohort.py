#!/usr/bin/env python3
"""Exercise startup readiness and finite cohort traffic against upstream sockets."""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
from ready import wait_ready
from switching import run


def check(binary):
    ports = []
    sockets = []
    for _ in range(5):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(('127.0.0.1', 0))
        sockets.append(s)
        ports.append(s.getsockname()[1])
    for s in sockets:
        s.close()
    # Closed endpoints cannot count as ready, and the deadline is bounded.
    assert not wait_ready('127.0.0.1', [ports[0]], timeout=0.1)['passed']
    processes = []
    errors = []
    def start():
        try:
            for port in ports:
                command = [binary, '-p', '0', '-U', str(port), '-t', '1', '-m', '16',
                           '-o', 'hashpower=18']
                if os.geteuid() == 0:
                    command += ['-u', 'root']
                processes.append(subprocess.Popen(command, stdout=subprocess.DEVNULL))
        except Exception as exc:
            errors.append(exc)
    timer = threading.Timer(0.2, start)
    timer.start()
    try:
        ready = wait_ready('127.0.0.1', ports, timeout=10)
        timer.join()
        assert not errors, errors
        assert ready['passed'] and any(not a['ready'] for a in ready['attempts'])
        with tempfile.TemporaryDirectory() as tmp:
            result = run('127.0.0.1', ports, Path(tmp)/'cohort', rounds=2)
            assert result['passed'], result.get('error')
            assert len(result['compatibility']) == 5
            assert all(p.poll() is None for p in processes)
        print('COHORT_TEST_PASS: delayed startup, deadline, five distinct instances, compatibility and traffic')
    finally:
        timer.join()
        for process in processes:
            process.terminate()
        for process in processes:
            process.wait(timeout=5)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--upstream', required=True)
    check(p.parse_args().upstream)
