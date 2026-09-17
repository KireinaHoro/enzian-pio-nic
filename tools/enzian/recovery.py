#!/usr/bin/env python3
"""No-reset B4 process recovery campaign on an already verified Enzian.

Run test.py first with the matching image and a persistent mul service. This
runner owns only the recovery-test processes it launches. It never programs,
resets, unloads the module, or displaces an attached console session.
"""
import argparse
import json
import pathlib
import shlex
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--logs', type=pathlib.Path, required=True)
    p.add_argument('--revision', required=True)
    p.add_argument('--gateway', default='enzian-gateway')
    p.add_argument('--machine', default='zuestoll14')
    p.add_argument('--ip', default='192.168.129.200')
    p.add_argument('--client', required=True, help='matching bench_client path on gateway')
    p.add_argument('--repeats', type=int, default=2)
    args = p.parse_args()
    if args.repeats < 1 or len(args.revision) != 40 or any(c not in '0123456789abcdef' for c in args.revision):
        p.error('positive repeats and full lowercase revision required')
    args.logs.mkdir(parents=True, exist_ok=False)
    remote = '/var/tmp/lh-recovery-' + args.logs.name
    q = shlex.quote
    results = []
    sequence = 0

    def cpu(body):
        nonlocal sequence
        sequence += 1
        script = args.logs / f'{sequence:03d}.sh'
        script.write_text('set -euo pipefail\n' + body + '\n')
        subprocess.run([sys.executable, str(HERE / 'run.py'), '--gateway', args.gateway,
                        '--machine', args.machine, '--logs', str(args.logs / f'{sequence:03d}-console'),
                        '--timeout', '90', '--script', str(script)], check=True, timeout=120)

    def client(op, count=10):
        command = shlex.join(['timeout', '45', args.client, args.ip, op, str(count)])
        with (args.logs / f'{sequence:03d}-{op}.log').open('a') as log:
            subprocess.run(['ssh', '-o', 'BatchMode=yes', args.gateway, command],
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=55)

    def save():
        (args.logs / 'summary.json').write_text(json.dumps({
            'revision': args.revision, 'machine': args.machine, 'remote_logs': remote,
            'reset': False, 'cases': results,
            'scope': 'incoming request/process recovery; nested deadline/cancel board tests remain separate'
        }, indent=2) + '\n')

    cpu(f'''test "$(/nix/store/lauberhorn/bin/jq -er .platform.revision /nix/store/lauberhorn/manifest.json)" = {q(args.revision)}
mkdir {q(remote)}
mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
echo 16384 > /sys/kernel/tracing/buffer_size_kb
echo 1 > /sys/kernel/tracing/events/lauberhorn/enable
echo 1 > /sys/kernel/tracing/tracing_on
dmesg > {q(remote)}/dmesg-before.log''')
    client('mul')
    cases = [('normal', 0, signal, active) for active in (False, True)
             for signal in ('INT', 'TERM', 'KILL')]
    cases += [('hang', 0, 'explicit', True), ('dirty-tx', 0, 'explicit', True),
              ('busy', 0, 'explicit', True), ('hang', 10000, 'watchdog', True)]
    try:
        for repeat in range(args.repeats):
            for mode, budget, action, active in cases:
                name = f'{repeat + 1:02d}-{mode}-{action}-{"active" if active else "idle"}'
                result = dict(name=name, status='running')
                results.append(result)
                save()
                print(json.dumps(result), flush=True)
                case = remote + '/' + name
                stop_ms = 15000 if action == 'explicit' else 0
                cpu(f'''mkdir {q(case)}
nohup /nix/store/lauberhorn/apps/recovery-test/bin/recovery-test {mode} {budget} 1 {stop_ms} > {q(case)}/server.log 2>&1 < /dev/null &
pid=$!
echo "$pid" > {q(case)}/pid
for attempt in $(seq 1 100); do
    grep -q RECOVERY_READY {q(case)}/server.log && break
    kill -0 "$pid"
    sleep .1
done
grep -q RECOVERY_READY {q(case)}/server.log''')
                if active and mode == 'normal':
                    client('add')
                elif active:
                    # Exactly one valid ONC-RPC request; no retries after recovery.
                    sender = ('import socket,struct; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); '
                              's.sendto(struct.pack("!13I",0xB4000001,0,2,0x23451111,1,1,0,0,0,0,1,2,3),'
                              + repr((args.ip, 12345)) + ')')
                    subprocess.run(['ssh', args.gateway, shlex.join(['python3', '-c', sender])],
                                   check=True, timeout=15)
                client('mul')
                signal = '' if action in ('explicit', 'watchdog') else f'kill -{action} "$pid"\n'
                dispatched = '' if not active else f'grep -E "worker_switch:.*next=[0-9]+:$pid/" {q(case)}/kernel-trace.log\n'
                cpu(f'''pid=$(cat {q(case)}/pid)
{signal}for attempt in $(seq 1 300); do
    test ! -e "/proc/$pid" && break
    sleep .1
done
test ! -e "/proc/$pid"
cat /sys/kernel/tracing/trace > {q(case)}/kernel-trace.log
grep -E "process_recovery:.*pid=$pid .*phase=2" {q(case)}/kernel-trace.log
{dispatched}dmesg > {q(case)}/dmesg.log
cat /sys/kernel/tracing/per_cpu/cpu*/stats > {q(case)}/trace-stats.log''')
                client('mul')
                result['status'] = 'pass'
                save()
                print(json.dumps(result), flush=True)
    except (subprocess.SubprocessError, OSError) as exc:
        if results and results[-1]['status'] == 'running':
            results[-1].update(status='fail', error=str(exc))
        save()
        # Preserve the failure; never hide it by resetting or killing other tasks.
        return 1
    save()
    return 0


if __name__ == '__main__':
    sys.exit(main())
