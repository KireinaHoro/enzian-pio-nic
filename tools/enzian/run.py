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
    # Short base64 lines avoid the serial terminal's canonical input limit.
    encoded = base64.b64encode(body.encode()).decode()
    payload = "base64 -d <<'ENZIAN_PAYLOAD' | bash\n"
    payload += '\n'.join(encoded[i:i+512] for i in range(0, len(encoded), 512))
    payload += "\nENZIAN_PAYLOAD\nprintf '\\n" + marker + ":%s\\n' \"$?\"\n"
    require_reservation(args.gateway, args.machine)
    args.logs.mkdir(parents=True, exist_ok=False)
    with (args.logs / 'console.log').open('w') as log:
        with attach(args.gateway, args.machine, 'console', log) as cpu:
            login_root(cpu, args.user, os.environ.get(args.password_env, 'enzian'))
            # Suppress command echo so assertions can only match actual output.
            cpu.sendline('stty -echo')
            cpu.expect(r'[^\r\n]+# ')
            try:
                for line in payload.splitlines():
                    cpu.sendline(line)
                if args.expect:
                    for expression in args.expect:
                        cpu.expect(expression, timeout=args.timeout)
                    cpu.expect(pexpect.TIMEOUT, timeout=3)
                    print('EXPECTED_OUTPUT_CONFIRMED')
                else:
                    cpu.expect(marker + r':(\d+)', timeout=args.timeout)
                    status = int(cpu.match.group(1))
                    if status:
                        raise RuntimeError(f'Remote command exited {status}')
                    print('COMMAND_SUCCEEDED')
            finally:
                cpu.sendline('stty echo')


if __name__ == '__main__':
    main()
