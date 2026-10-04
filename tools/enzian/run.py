#!/usr/bin/env python3
"""Run a shell command or local script as root, retaining the CPU serial log.

Use repeated --expect expressions for ordered console assertions (e.g. an oops).
Without assertions, require a zero command exit status. No boot or reset occurs.
"""
import argparse
import base64
import hashlib
import os
import pathlib
import re
import shlex
import uuid
import time

import pexpect
from console import attach, login_root, require_reservation


def make_payload(body, marker):
    encoded = base64.b64encode(body.encode()).decode()
    digest = hashlib.sha256(body.encode()).hexdigest()
    # Parse the whole group before running it. A dropped serial byte must not
    # turn a partial script into board actions: verify decoded bytes first.
    lines = ['{', 'lh_payload=$(mktemp)',
             "base64 -d >\"$lh_payload\" <<'ENZIAN_PAYLOAD'"]
    lines += [encoded[i:i+128] for i in range(0, len(encoded), 128)]
    lines += ['ENZIAN_PAYLOAD',
              "if printf '%s  %s\\n' " + digest + ' "$lh_payload" | sha256sum --check --status; then',
              'bash "$lh_payload" </dev/null', 'lh_status=$?',
              'else', 'lh_status=125', 'echo PAYLOAD_CHECKSUM_MISMATCH', 'fi',
              'rm -f "$lh_payload"',
              "printf '\\n" + marker + ":%s\\n' \"$lh_status\"", '}']
    return '\n'.join(lines) + '\n'


def send_line(cpu, line, chunk_size=8):
    # Keep bursts below a small UART FIFO. Prompt acknowledgments and the
    # complete-script checksum establish receipt; pacing alone cannot do so.
    previous = cpu.delaybeforesend
    cpu.delaybeforesend = 0
    try:
        for offset in range(0, len(line), chunk_size):
            cpu.send(line[offset:offset+chunk_size])
            time.sleep(.02)
        cpu.send('\n')
    finally:
        cpu.delaybeforesend = previous


def prepare_input(cpu, prompt):
    # The initial setup is outside the checksummed payload. A dropped PS2
    # assignment makes every subsequent continuation acknowledgment fail.
    # Keep commands short, send one byte at a time, and await each root prompt.
    for command in ('stty -echo', 'PS2=' + shlex.quote(prompt)):
        send_line(cpu, command, chunk_size=1)
        cpu.expect(r'[^\r\n]+# ')


def send_payload(cpu, payload, prompt):
    lines = payload.splitlines()
    for index, line in enumerate(lines):
        send_line(cpu, line)
        if index != len(lines) - 1:
            # Kernel printk can stall serial input. A fixed delay between
            # writes does not establish that the shell received the last line.
            cpu.expect_exact(prompt)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--gateway', default='enzian-gateway')
    p.add_argument('--machine', default='zuestoll14')
    p.add_argument('--user', default='enzian')
    p.add_argument('--password-env', default='ENZIAN_PASSWORD',
                   help='environment variable for console password (default: documented enzian password)')
    p.add_argument('--logs', type=pathlib.Path, required=True)
    p.add_argument('--script', type=pathlib.Path, help='local shell script; remaining arguments are passed to it')
    p.add_argument('--expect', action='append', default=[], help='ordered console regex; overrides exit-status check')
    p.add_argument('--timeout', type=int, default=180)
    p.add_argument('command', nargs=argparse.REMAINDER)
    args = p.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not args.script and not command:
        p.error('a command or --script is required')
    for expression in args.expect:
        re.compile(expression)
    if args.script:
        body = 'set -- ' + shlex.join(command) + '\n' + args.script.read_text()
    else:
        body = shlex.join(command)
    marker = 'ENZIAN_DONE_' + uuid.uuid4().hex
    payload = make_payload(body, marker)
    input_prompt = 'LH_' + uuid.uuid4().hex[:8] + '> '
    require_reservation(args.gateway, args.machine)
    args.logs.mkdir(parents=True, exist_ok=False)
    with (args.logs / 'console.log').open('w') as log:
        with attach(args.gateway, args.machine, 'console', log) as cpu:
            login_root(cpu, args.user, os.environ.get(args.password_env, 'enzian'))
            # Suppress command echo so assertions can only match actual output.
            prepare_input(cpu, input_prompt)
            command_started = False
            try:
                send_payload(cpu, payload, input_prompt)
                command_started = True
                if args.expect:
                    for expression in args.expect:
                        cpu.expect(expression, timeout=args.timeout)
                    cpu.expect(pexpect.TIMEOUT, timeout=3)
                    print('EXPECTED_OUTPUT_CONFIRMED')
                else:
                    cpu.expect(marker + r':(\d+)\r?\n', timeout=args.timeout)
                    status = int(cpu.match.group(1))
                    if status:
                        raise RuntimeError(f'Remote command exited {status}')
                    print('COMMAND_SUCCEEDED')
            finally:
                if not command_started:
                    # Only an unexecuted compound command can be cancelled here.
                    cpu.sendcontrol('c')
                    cpu.expect(r'[^\r\n]+# ', timeout=10)
                send_line(cpu, "PS2='> '; stty echo")


if __name__ == '__main__':
    main()
