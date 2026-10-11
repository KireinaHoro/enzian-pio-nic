#!/usr/bin/env python3
"""Start one bounded cohort after harness bring-up; never recycle native domains."""
import argparse
import hashlib
import json
import os
import signal
from pathlib import Path
import subprocess
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--deployment', type=Path, default=Path('/nix/store/lauberhorn'))
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--case', type=int, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--ordinary-cpus', required=True, help='Explicit CPU list, e.g. 0-43')
    p.add_argument('--worker-cpus', type=int, nargs='+', required=True)
    p.add_argument('--hashpower', type=int, default=18)
    p.add_argument('--trace', action='store_true')
    a = p.parse_args()
    case = json.loads(a.manifest.read_text())['cases'][a.case]
    ordinary = set()
    for part in a.ordinary_cpus.split(','):
        ends = [int(n) for n in part.split('-')]
        ordinary.update(range(ends[0], ends[-1]+1))
    if not ordinary or ordinary.intersection(a.worker_cpus):
        p.error('main/background CPUs must be disjoint from physical workers')
    if len(a.worker_cpus) != case['physical_workers'] or len(set(a.worker_cpus)) != len(a.worker_cpus):
        p.error('physical worker CPU mapping does not match manifest')
    if not 16 <= a.hashpower <= 24 or any(not 1 <= n <= len(a.worker_cpus) for n in case['workers']):
        p.error('worker count or bounded hashpower invalid')
    deployment = json.loads((a.deployment/'manifest.json').read_text())
    binary = deployment['applications']['memcached']['entryPoint']
    a.output = a.output.resolve()
    a.output.mkdir(parents=True, exist_ok=False)
    records = []
    # Memcached starts maintenance/request threads before lauberhorn_init().
    # They must inherit the dump-signal mask too: a process-directed SIGUSR2
    # otherwise terminates an unblocked early thread instead of reaching the
    # runtime's sigwait dump thread. The runtime keeps this signal blocked.
    if a.trace:
        signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGUSR2})
    # Affinity is inherited by main/maintenance threads. Registration assigns
    # request workers through the kernel's physical-worker mapping.
    os.sched_setaffinity(0, ordinary)
    for workers, port in zip(case['workers'], case['ports'], strict=True):
        env = os.environ.copy()
        if a.trace:
            env['LAUBERHORN_SERVER_TRACE_CSV'] = str(a.output/f'server-{port}.csv')
            env['LAUBERHORN_EVENT_TRACE_CSV'] = str(a.output/f'events-{port}.csv')
        else:
            for name in ('LAUBERHORN_SERVER_TRACE_CSV','ADDER_SERVER_TRACE_CSV','LAUBERHORN_EVENT_TRACE_CSV'):
                env.pop(name, None)
        command = [binary, '-p', '0', '-U', str(port), '-t', str(workers), '-m', '128',
                   '-o', f'hashpower={a.hashpower}']
        if os.geteuid() == 0: command += ['-u', 'root']
        with (a.output/f'server-{port}.log').open('xb') as log:
            proc = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
        records.append(dict(pid=proc.pid, port=port, workers=workers, command=command))
        # Persist every started PID even if a subsequent launch fails. Cleanup
        # belongs to the documented fenced harness lifecycle, not this tool.
        result = dict(schema=1, case=case, processes=records, deployment=deployment,
                      ordinary_cpus=sorted(ordinary), worker_cpus=a.worker_cpus,
                      tracing=a.trace, readiness='unverified: require remote compatibility gate',
                      manifest_sha256=hashlib.sha256(a.manifest.read_bytes()).hexdigest())
        (a.output/'launch.json').write_text(json.dumps(result, indent=2)+'\n')
        time.sleep(.1)
        if proc.poll() is not None:
            raise RuntimeError(f'port {port} exited {proc.returncode}; preserve logs before recovery')
    print(json.dumps(dict(pids=[r['pid'] for r in records], readiness='unverified')))


if __name__ == '__main__': main()
