#!/usr/bin/env python3
"""Generate TCP/DEADLINE/memcached cases for the shared hardware test harness.

This only writes a manifest. Reservation, provisioning and boot remain separate
operations owned by the Enzian deployment workflow.
"""
import argparse
import ipaddress
import json
from pathlib import Path
import re
import shlex


def manifest(args):
    q = shlex.quote
    address = str(ipaddress.IPv4Interface(args.cidr).ip)
    prefix = '/nix/store/lauberhorn'
    fixtures = prefix + '/apps/transport-validation'
    scripts = fixtures + '/share/lauberhorn-transport'
    memcached = prefix + '/apps/memcached/bin/memcached'
    common = f'''export PATH={prefix}/bin:{prefix}/sbin:$PATH
cpu configure {q(args.mac)} {q(args.cidr)}
mkdir -p /tmp/lh-{{run}}
wait_ready() {{
    for attempt in $(seq 1 100); do
        if grep -q "$1" "$2"; then return 0; fi
        sleep 0.1
    done
    return 1
}}
'''
    # Refuse a different mounted closure; never replace a peer's live image.
    peer = f'''set -eu
if ! mountpoint -q /nix/store; then
    mountpoint -q /scratch || mount /scratch
    mkdir -p /nix/store
    mount -t squashfs -o loop,ro {q(args.image)} /nix/store
fi
export PATH={prefix}/bin:{prefix}/sbin:$PATH
test "$(jq -er .platform.revision {prefix}/manifest.json)" = {q(args.revision)}
export LH_TEST_HOST={q(address)} LH_TEST_INTERFACE={q(args.peer_interface)}
'''
    health = '''dmesg > /tmp/lh-{run}/dmesg.log
! grep -E 'BUG:|Oops:|Kernel panic|Internal error:' /tmp/lh-{run}/dmesg.log
'''

    def launch(name, argv, ready):
        log = '/tmp/lh-{run}/' + name + '.log'
        return (f'nohup taskset -c 0-1 {shlex.join(argv)} > {log} 2>&1 < /dev/null &\n'
                f'echo $! > /tmp/lh-{{run}}/{name}.pid\n'
                f'wait_ready {q(ready)} {log}\n')

    def alive(*names):
        return ''.join(f'kill -0 "$(cat /tmp/lh-{{run}}/{name}.pid)"\n' for name in names) + health

    def case(name, cpu, client='', post=health):
        result = dict(name=name, revision=args.revision, image=args.image,
                      program=args.program, cpu=common + cpu, post_cpu=post)
        if client:
            result.update(client_machine=args.peer, client=peer + client)
        return result

    def mc(port, udp=False):
        return [memcached, '-l', address, '-p', '0' if udp else str(port),
                '-U', str(port) if udp else '0', '-t', '4', '-c', '256',
                '-m', '32', '-o', 'hashpower=16', '-u', 'root']

    raw_client = ('import errno, os, socket; s=socket.socket(); s.settimeout(10); '
                  'assert s.connect_ex((os.environ["LH_TEST_HOST"],19500)) == errno.ECONNREFUSED')
    cases = [
        case('deadline', f'timeout 30 taskset -c 0-1 {fixtures}/bin/deadline-validation\n'),
        case('tcp-transport', launch('transport', [fixtures + '/bin/tcp-transport', address], 'READY'),
             'python3 -c ' + q(raw_client) + '\n',
             'grep -q TCP_TRANSPORT_PASS /tmp/lh-{run}/transport.log\n' + alive('transport')),
        case('tcp-protocol', launch('tcp', [fixtures + '/bin/tcp-server', address, '19501'], 'TCP_READY'),
             f'python3 {scripts}/tcp.py\npython3 {scripts}/tcp_reorder.py\n', alive('tcp')),
        case('memcached-tcp', launch('memcached', mc(11212), 'LAUBERHORN_READY TCP'),
             f'python3 {scripts}/memcached_tcp.py\n', alive('memcached')),
        case('memcached-bursts', ''.join(launch(f'mc-{port}', mc(port), 'LAUBERHORN_READY TCP')
                                       for port in range(11220, 11224)),
             f'python3 {scripts}/memcached_bursts.py\n', alive(*(f'mc-{p}' for p in range(11220, 11224)))),
        case('memcached-udp', launch('memcached', mc(11211, udp=True), 'LAUBERHORN_READY UDP'),
             f'python3 {scripts}/memcached.py\n', alive('memcached')),
    ]
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--image', required=True, help='shared /scratch deployment squashfs')
    parser.add_argument('--program', required=True, type=Path, help='JSON programmer argv file')
    parser.add_argument('--cidr', required=True, help='DUT Lauberhorn IPv4 address/prefix')
    parser.add_argument('--mac', required=True)
    parser.add_argument('--peer', required=True, help='reserved Linux peer board')
    parser.add_argument('--peer-interface', required=True, help='peer interface routing to the DUT')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch('[0-9a-f]{40}', args.revision):
        parser.error('revision must be a full commit')
    if not re.fullmatch(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', args.mac):
        parser.error('invalid MAC address')
    args.program = json.loads(args.program.read_text())
    if not isinstance(args.program, list) or not args.program or not all(isinstance(a, str) for a in args.program):
        parser.error('program must contain a nonempty JSON argv array')
    with args.output.open('x') as stream:
        json.dump(manifest(args), stream, indent=2)
        stream.write('\n')


if __name__ == '__main__':
    main()
