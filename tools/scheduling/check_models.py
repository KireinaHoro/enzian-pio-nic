#!/usr/bin/env python3
"""Run the bounded scheduling contracts with locked Nix TLC; preserve failures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
ROOT = Path(__file__).resolve().parents[2]
# Values name deliberate faults. Each negative must violate its named obligation.
CASES = {
    'handoff': ('Handoff', {}, None),
    'handoff-rx-tx': ('Handoff', {'WorkerTx': True}, None),
    'handoff-stalled-peer': ('Handoff', {'WithStall': True}, None),
    'unavailable': ('Handoff', {'FailSelection': True}, None),
    'bad-drain': ('Handoff', {'BadDrain': True}, 'DrainBeforeInstall'),
    'bad-cache': ('Handoff', {'BadCache': True}, 'CacheBeforeInstall'),
    'bad-park': ('Handoff', {'BadPark': True}, 'Safety'),
    'bad-prefix-reuse': ('Handoff', {'BadReuse': True}, 'PrefixSafe'),
    'bad-parity': ('Handoff', {'BadParity': True}, 'ActivationSafe'),
}
CASES.update({
    'reservation': ('Reservation', {}, None),
    'reservation-unavailable': ('Reservation', {'FailSelection': True}, None),
    'bad-reservation': ('Reservation', {'BadReservation': True}, 'Safety'),
    'host-wake': ('HostWake', {}, None),
    'host-wake-parked-shared': ('HostWake', {'SharedSubscription': True, 'InitiallyParked': True}, None),
    'bad-wake-clear': ('HostWake', {'BadClear': True}, 'Responsibility'),
    'bad-wake-reassign': ('HostWake', {'BadReassign': True}, 'Responsibility'),
    'bad-wake-subscriber': ('HostWake', {'BadSubscriber': True}, 'EligibleDelivery'),
    'interrupt': ('InterruptGate', {}, None),
    'bad-rearm': ('InterruptGate', {'BadRearm': True}, 'NoPrematureReblock'),
    'bad-release': ('InterruptGate', {'BadRelease': True}, 'NoPrematureReblock'),
    'bad-interrupt-progress': ('InterruptGate', {'BadRearm': True}, 'InterruptProgress'),
    'thread-claim': ('ThreadClaim', {}, None),
    'bad-thread-claim': ('ThreadClaim', {'BadClaim': True}, 'UniqueClaim'),
    'wake-handoff': ('WakeHandoff', {}, None),
    'wake-retirement': ('WakeRetirement', {}, None),
    'bad-wake-retirement': ('WakeRetirement', {'BadRetirement': True}, 'PrefixSafe'),
    'timer-mirror': ('TimerMirror', {}, None),
    'bad-timer-order': ('TimerMirror', {'BadOrder': True}, 'MirrorOrder'),
})
DEFAULTS = {
    'Handoff': dict.fromkeys(('WorkerTx','WithStall','FailSelection','BadDrain','BadCache','BadPark','BadReuse','BadParity'), False),
    'Reservation': {'BadReservation': False, 'FailSelection': False},
    'HostWake': dict.fromkeys(('BadClear','BadReassign','BadSubscriber','SharedSubscription','InitiallyParked'), False),
    'InterruptGate': {'BadRearm': False, 'BadRelease': False},
    'TimerMirror': {'BadOrder': False},
    'ThreadClaim': {'BadClaim': False},
    'WakeRetirement': {'BadRetirement': False},
    'WakeHandoff': {},
}
INVARIANTS = {
    'Handoff': ['Safety','DrainBeforeInstall','CacheBeforeInstall','PrefixSafe','SteadyEntry'],
    'Reservation': ['Safety','NoLeakedReservation'],
    'HostWake': ['TypeOK','Responsibility','EligibleDelivery','HotReservation'],
    'InterruptGate': ['TypeOK','NoPrematureReblock'],
    'TimerMirror': ['TypeOK','MirrorOrder','Shutdown'],
    'ThreadClaim': ['TypeOK','UniqueClaim','ClaimAvailable'],
    'WakeRetirement': ['Safety','PrefixSafe'],
    'WakeHandoff': ['Safety'],
}
PROPERTIES = {
    'Handoff': ['ActivationSafe','SleepRetainsAssignment','HandoffFinishes','PeerProgress','PrefixRetires'],
    'Reservation': ['ReservationFinishes'],
    'HostWake': ['WakeProgress','SleepRetainsOwner'],
    'InterruptGate': ['InterruptProgress'],
    'TimerMirror': ['MirrorCatchesUp'],
    'ThreadClaim': ['SelectionFinishes'],
    'WakeRetirement': ['Retires'],
    'WakeHandoff': ['ActivationSafe','WakeCompletes','PeerProgress'],
}

def config(module, options, negative):
    constants = DEFAULTS[module] | options
    invs, props = INVARIANTS[module], PROPERTIES[module]
    if negative:
        invs = [negative] if negative in invs else []
        props = [negative] if negative in props else []
    return ('SPECIFICATION Spec\n' + ('CONSTANTS\n' if constants else '') + ''.join(f'{k} = {str(v).upper()}\n' for k,v in constants.items())
            + ('INVARIANTS ' + ' '.join(invs) + '\n' if invs else '')
            + ('PROPERTIES ' + ' '.join(props) + '\n' if props else '')
            + 'CHECK_DEADLOCK FALSE\n')

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--case', action='append', choices=CASES)
    p.add_argument('--timeout', type=int, default=300)
    p.add_argument('--heap-mib', type=int, default=2048)
    p.add_argument('--workers', type=int, default=1)
    args = p.parse_args()
    jar = os.environ.get('TLA2TOOLS_JAR')
    if not jar: p.error('run through nix develop: TLA2TOOLS_JAR is required')
    if min(args.timeout,args.heap_mib,args.workers) <= 0: p.error('bounds must be positive')
    args.output.mkdir(parents=True,exist_ok=False)
    sources = sorted((ROOT/'model/scheduling').glob('*.tla')) + sorted((ROOT/'model/steady').glob('*.tla'))
    sources += sorted((ROOT/'tools/scheduling').glob('*.py')) + [ROOT/'model/steady/selected-8k.json', ROOT/'tools/progress/capacity.py']
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    snapshot = {f: f.read_bytes() for f in sources}
    summary = {'jar_sha256': sha(Path(jar)), 'sources': {str(f.relative_to(ROOT)): hashlib.sha256(data).hexdigest() for f,data in snapshot.items()},
               'java_version': subprocess.run(['java','-version'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=True).stdout,
               'cases': {}}
    for name in args.case or [name for name in CASES if name not in {'handoff-stalled-peer', 'handoff-rx-tx'}]:
        module, options, negative = CASES[name]
        work = args.output/name
        work.mkdir()
        for path in sources:
            if path.suffix == '.tla': (work/path.name).write_bytes(snapshot[path])
        cfg = work/(module+'.cfg')
        cfg.write_text(config(module,options,negative))
        cmd = ['java',f'-Xmx{args.heap_mib}m','-XX:+UseParallelGC','-cp',jar,'tlc2.TLC',
               '-workers',str(args.workers),'-lncheck','final','-metadir',str((work/'states').resolve()),
               '-teSpecOutDir',str(work.resolve()),'-config',str(cfg.resolve()),str((work/(module+'.tla')).resolve())]
        print(f'{name}: checking',flush=True)
        start = time.monotonic()
        log = work/'TLC.log'
        with log.open('w') as out:
            try: code = subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT,timeout=args.timeout).returncode
            except subprocess.TimeoutExpired:
                code = 124
                out.write('\nINCOMPLETE: timeout\n')
        output = log.read_text()
        expected = bool(negative) and ((code == 12 and f'Invariant {negative} is violated' in output)
                    or (code == 13 and negative in PROPERTIES[module] and ('Temporal properties were violated' in output or f'Action property {negative} is violated' in output)))
        passed = expected if negative else code == 0 and 'No error has been found' in output
        result = dict(passed=passed, expected_violation=negative, returncode=code,
                      config_sha256=sha(cfg), log_sha256=sha(log), command=cmd,
                      elapsed_seconds=round(time.monotonic()-start,3),
                      statistics=[line for line in output.splitlines() if any(x in line for x in ('distinct states found','depth of the complete'))])
        summary['cases'][name] = result
        (args.output/'summary.json').write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n')
        print(f'{name}: {"PASS" if passed else "INCOMPLETE" if code == 124 else "FAIL"}',flush=True)
        if not passed: print('\n'.join(output.splitlines()[-28:]),flush=True)
    return 0 if all(c['passed'] for c in summary['cases'].values()) else 1
if __name__ == '__main__': raise SystemExit(main())
