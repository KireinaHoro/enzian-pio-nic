#!/usr/bin/env python3
"""Run a shell command or local script as root, retaining the CPU serial log.

Use repeated --expect expressions for ordered console assertions (e.g. an oops).
Without assertions, require a zero command exit status. No boot or reset occurs.
"""
import argparse
import base64
import os
import pathlib
import re
import shlex
import uuid

import pexpect
from console import attach, login_root, require_reservation


def make_payload(body, marker):
    encoded = base64.b64encode(body.encode()).decode()
    # Receive the completion command before starting the script. Keep serial
    # input bursts short, and wait for the shell to consume each line below.
    payload = "base64 -d <<'ENZIAN_PAYLOAD' | bash; printf '\\n" + marker + ":%s\\n' \"$?\"\n"
    payload += '\n'.join(encoded[i:i+128] for i in range(0, len(encoded), 128))
    return payload + "\nENZIAN_PAYLOAD\n"


def send_payload(cpu, payload, prompt):
    lines = payload.splitlines()
    for index, line in enumerate(lines):
        cpu.sendline(line)
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
    input_prompt = 'ENZIAN_INPUT_' + uuid.uuid4().hex + '> '
    require_reservation(args.gateway, args.machine)
    args.logs.mkdir(parents=True, exist_ok=False)
    with (args.logs / 'console.log').open('w') as log:
        with attach(args.gateway, args.machine, 'console', log) as cpu:
            login_root(cpu, args.user, os.environ.get(args.password_env, 'enzian'))
            # Suppress command echo so assertions can only match actual output.
            cpu.sendline('stty -echo; PS2=' + shlex.quote(input_prompt))
            cpu.expect(r'[^\r\n]+# ')
            try:
                send_payload(cpu, payload, input_prompt)
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
                cpu.sendline("PS2='> '; stty echo")


if __name__ == '__main__':
    main()
