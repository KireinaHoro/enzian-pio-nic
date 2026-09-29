#!/usr/bin/env python3
"""SSH to leaf1 through enzian-gateway and agg0, prompting for switch passwords.

Run without arguments for an interactive shell, or pass a command after --.
Passwords and host-key verification are handled by SSH on each hop.
"""
import argparse
import os
import shlex


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', default='enzian-gateway')
    parser.add_argument('--user', default='admin', help='SSH user on both switches')
    parser.add_argument('--print-command', action='store_true', help='print instead of connecting')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    options = ['ssh', '-tt', '-o', 'ConnectTimeout=15']
    leaf = options + [args.user + '@fe80::290:fbff:fe73:e4a1%eth0']
    if command:
        leaf.append(shlex.join(command))
    agg = options + [args.user + '@fe80::290:fbff:fe73:e4b6%cluster0.103', shlex.join(leaf)]
    gateway = options + [args.gateway, shlex.join(agg)]
    if args.print_command:
        print(shlex.join(gateway))
    else:
        os.execvp(gateway[0], gateway)


if __name__ == '__main__':
    main()
