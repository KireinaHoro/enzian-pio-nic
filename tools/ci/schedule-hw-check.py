#!/usr/bin/env python3
"""Schedule one CI follow-up in the current Codex thread using a user timer."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('pipeline', type=int)
    p.add_argument('--stage', choices=['preflight', 'results', 'results-recheck'], default='preflight')
    p.add_argument('--thread', default=os.environ.get('CODEX_THREAD_ID'))
    p.add_argument('--dry-run', action='store_true')
    p.add_argument('--rearm', action='store_true', help='replace this stage timer, never a running queue invocation')
    a = p.parse_args()
    if a.pipeline <= 0 or not a.thread:
        p.error('positive pipeline ID and --thread (or CODEX_THREAD_ID) are required')
    codex = shutil.which('codex')
    if not codex:
        p.error('codex must be installed on this host')
    delay = '5h' if a.stage == 'results' else '30m'
    unit = f'lauberhorn-ci-{a.pipeline}-{a.stage}'
    prompt = (
        f'Scheduled {a.stage} follow-up for Lauberhorn pipeline {a.pipeline}. '
        f'Work in {ROOT}. Read AGENTS.md and docs/development/ci.md '
        '(Scheduled hardware-build follow-up), then follow that workflow. '
        'The user authorized checking this CI build and testing its hardware on Enzian. '
        'Use read-only CI queries; never use ../.gitlab_token for writes. '
        'Inspect current state before launching duplicate builds or tests. '
    )
    if a.stage == 'preflight':
        prompt += (
            'Check all gating tests and prepare-eci. If they passed and build-hw-eci '
            'is running, schedule the results follow-up FIVE HOURS FROM NOW using '
            f'python3 tools/ci/schedule-hw-check.py {a.pipeline} --stage results '
            f'--thread {a.thread}; do not poll during that interval. '
            'If tests/preparation failed, inspect the failed stage, fix and validate '
            'only a trivial cause, relaunch and arm a new 30-minute check. For a '
            'nontrivial failure identify the problem, report it and stop. If still '
            f'pending, re-arm with python3 tools/ci/schedule-hw-check.py {a.pipeline} '
            f'--stage preflight --rearm --thread {a.thread}. If hardware already succeeded, '
            'collect and test now; if it failed, diagnose and report. '
        )
    else:
        prompt += (
            'Collect hardware results and matching bitstream/software/trace artifacts. '
            'If the build succeeded, verify reservation and run the authorized matched '
            'Enzian RPC test with the repository harness (at least two repeats). '
            'Preserve any existing frozen failure capture before resetting, keep tracing '
            'armed through module load, and capture failures before any reset. '
            'Read compact stage output and summary.json; inspect raw logs only for errors. '
            f'If build is still running, run python3 tools/ci/schedule-hw-check.py {a.pipeline} '
            f'--stage results-recheck --rearm --thread {a.thread} for a 30-minute '
            'recheck; do not start another five-hour delay. If build failed, diagnose and report. '
        )
    prompt += (
        'Keep the pipeline branch until all jobs finish. Do not change timing, address '
        'layout or PREEMPT implementation as part of this follow-up. Match the tested '
        'revision to the pipeline SHA, not the current master checkout. Record '
        'pipeline/job IDs, provenance, test outcomes and remaining limitations.'
    )
    command = [
        'systemd-run', '--user', '--collect', f'--unit={unit}',
        f'--on-active={delay}', '--timer-property=AccuracySec=1s',
        f'--working-directory={ROOT}', codex, 'queue', '--thread', a.thread,
        '--message', prompt,
    ]
    if a.dry_run:
        print(json.dumps({'unit': unit, 'delay': delay, 'command': command}, indent=2))
        return
    # A live timer or queue invocation must not be silently replaced/duplicated.
    for suffix in ('.timer', '.service'):
        state = subprocess.run(['systemctl', '--user', 'is-active', unit + suffix],
                               capture_output=True, text=True).stdout.strip()
        if state in {'active', 'activating', 'reloading'} and (suffix == '.service' or not a.rearm):
            raise SystemExit(f'{unit + suffix} already {state}; inspect before rearming')
    subprocess.run(['systemctl', '--user', 'stop', unit + '.timer', unit + '.service'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(command, check=True)
    subprocess.run(['systemctl', '--user', 'list-timers', unit + '.timer', '--no-pager'], check=True)


if __name__ == '__main__':
    main()
