#!/usr/bin/env python3
"""Repeat manifest-defined hardware tests; retain logs and emit compact JSON results.

Manifest: list of cases with name, image, revision, program (argv), cpu (shell
commands after mount/load/verify), and client (gateway shell command). Cases run
in listed order each round, always with a full power cycle. No CI polling.
"""
import argparse
import json
import os
import signal
import pathlib
import re
import shlex
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent


def run_step(command, log, timeout):
    with log.open('w') as stream:
        proc = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT,
                                start_new_session=True)
        try:
            return proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # Let console helpers detach in finally blocks; kill the entire
            # local process group if graceful interruption cannot finish.
            os.killpg(proc.pid, signal.SIGINT)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait()
            return 124


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('manifest', type=pathlib.Path)
    p.add_argument('--logs', type=pathlib.Path, required=True)
    p.add_argument('--repeats', type=int, default=1)
    p.add_argument('--gateway', default='enzian-gateway')
    p.add_argument('--machine', default='zuestoll14')
    args = p.parse_args()
    cases = json.loads(args.manifest.read_text())
    if args.repeats < 1 or not cases:
        p.error('positive repeat count and nonempty cases required')
    if len({case['name'] for case in cases}) != len(cases):
        p.error('case names must be unique')
    for case in cases:
        if not re.fullmatch(r'[A-Za-z0-9_-]+', case['name']):
            p.error('case name must be safe for paths')
        if not re.fullmatch(r'[0-9a-f]{40}', case['revision']):
            p.error('each case requires a full software/hardware revision')
        if not isinstance(case['program'], list) or not case['program']:
            p.error('program must be a nonempty argv list')
    args.logs.mkdir(parents=True, exist_ok=False)
    (args.logs / 'manifest.json').write_text(json.dumps(cases, indent=2)+'\n')
    results = []
    shared = ['--gateway', args.gateway, '--machine', args.machine]
    cpu_source = (HERE / 'cpu.sh').read_text()
    def save():
        (args.logs / 'summary.json').write_text(json.dumps(results, indent=2)+'\n')
    for round_no in range(1, args.repeats+1):
        for case in cases:
            name = f"{round_no:02d}-{case['name']}"
            root = args.logs / name
            root.mkdir()
            result = {'case': case['name'], 'round': round_no,
                      'revision': case['revision'], 'status': 'running'}
            results.append(result); save()
            print(json.dumps({'case': name, 'stage': 'boot'}), flush=True)
            started = time.monotonic()
            rc = run_step([sys.executable, str(HERE/'boot.py'), *shared,
                       '--logs', str(root/'boot'), '--', *case['program']],
                      root/'boot-output.log', 1100)
            result['boot_exit'] = rc
            boot_output = (root/'boot-output.log').read_text(errors='replace')
            result['reset_evidence'] = re.findall(r'POWER_OFF_VERIFIED [^\r\n]*', boot_output)
            if rc:
                result['status'] = 'infrastructure_failure'
                save(); print(json.dumps(result), flush=True)
                return 2
            if not rc:
                # Execute the reusable CPU operations within one console session.
                # Each function invocation gets an isolated shell for set -e/args.
                script = 'set -eu\ncpu() (\n' + cpu_source + '\n)\n'
                script += 'cpu mount ' + shlex.quote(case['image']) + '\n'
                script += 'test "$(/nix/store/lauberhorn/bin/jq -er .platform.revision /nix/store/lauberhorn/manifest.json)" = ' + shlex.quote(case['revision']) + '\n'
                script += "/nix/store/lauberhorn/bin/lh-test info --json\n"
                script += "echo TEST_STAGE:mount\ndmesg -n 8\ncpu load\necho TEST_STAGE:load\ncpu verify\necho TEST_STAGE:verify\n"
                script += case.get('cpu', '').replace('{run}', name) + '\n'
                script += 'echo TEST_STAGE:ready\n'
                script_path = root/'cpu-test.sh'; script_path.write_text(script)
                print(json.dumps({'case': name, 'stage': 'cpu'}), flush=True)
                rc = run_step([sys.executable, str(HERE/'run.py'), *shared, '--logs', str(root/'cpu'),
                           '--script', str(script_path), '--timeout', '180'], root/'cpu-output.log', 240)
                result['cpu_exit'] = rc
                transcript = (root/'cpu'/'console.log').read_text(errors='replace') if (root/'cpu'/'console.log').exists() else ''
                result['cpu_stages'] = re.findall(r'TEST_STAGE:(\w+)', transcript)
                result['faults'] = re.findall(r'(?:Internal error:[^\r\n]*|pc : [^\r\n]*|Kernel panic[^\r\n]*)', transcript)
            if not rc and case.get('client'):
                print(json.dumps({'case': name, 'stage': 'rpc'}), flush=True)
                rc = run_step(['ssh', '-o', 'BatchMode=yes', args.gateway,
                           case['client'].replace('{run}', name)], root/'client.log', 120)
                result['client_exit'] = rc
            result['status'] = 'pass' if not rc else 'fail'
            result['seconds'] = round(time.monotonic()-started, 1)
            save(); print(json.dumps(result), flush=True)
    return int(any(r['status'] != 'pass' for r in results))


if __name__ == '__main__':
    sys.exit(main())
