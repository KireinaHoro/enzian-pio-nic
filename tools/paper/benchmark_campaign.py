#!/usr/bin/env python3
"""Durable hot echo campaign controller. Hardware actions use a manifest adapter.

The adapter is invoked as ARGV... STAGE REQUEST.json RESULT.json. It must use
EMG/the repository harness, preserve failed boots, restore controls, and emit
validated evidence. No shell interpolation or automatic reuse of failed attempts.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from campaign.policy import SYSTEMS, selected_systems, SIZES, WORKERS, LOSS_LIMIT, loss, grid, next_rate, candidates
from campaign.import_loaded import load, digest
from campaign.import_unloaded import validate_matched
from campaign.recovery import prepare as prepare_recovery, invalidated

ROOT = Path(__file__).resolve().parents[2]
SELF = Path(__file__).resolve()


def atomic(path, value):
    temporary = path.with_suffix(path.suffix+'.tmp')
    with temporary.open('w') as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    temporary.replace(path)


def read(path):
    return json.loads(Path(path).read_text())


def _notify(root, config, retry=True):
    state = read(root/'status.json')
    if state['stage'] not in ('failed', 'complete', 'rehearsal-complete'):
        return
    notice = root/'notification.json'
    old = read(notice) if notice.exists() else {}
    identity = hashlib.sha256(json.dumps(state, sort_keys=True).encode()).hexdigest()
    if old.get('delivered') and old.get('identity') == identity:
        return
    message = (f"Echo campaign {state['stage']}: {root}. "
               f"Status: {root/'status.json'}. Latest: {state.get('latest')}. "
               f"Cohorts: {state.get('completed')}/{state.get('total')}. "
               f"Failed stage: {state.get('failed_stage')}. Error: {state.get('error')}. "
               f"Previews: {state.get('previews', [])}. "
               "Inspect preserved evidence on failure; visually review final plot previews on success.")
    argv = config.get('notify', ['codex', 'queue']) + ['--thread', config['thread'], '--message', message]
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        delivered, error = result.returncode == 0, result.stderr[-2000:]
    except (OSError, subprocess.TimeoutExpired) as exc:
        delivered, error = False, str(exc)
    atomic(notice, dict(identity=identity, delivered=delivered, attempts=old.get('attempts', 0)+1, error=error))
    if not delivered and retry:
        # Timer retries delivery only, never acquisition. Fixed unit prevents a fan-out.
        subprocess.run(['systemd-run', '--user', '--collect', '--on-active=60s',
                        '--unit', 'echo-notify-'+hashlib.sha256(str(root).encode()).hexdigest()[:12]+'-'+str(time.time_ns()),
                        sys.executable, str(SELF), 'notify', '--root', str(root)], check=True, timeout=15)
    return delivered


def notify(root, config, retry=True):
    with (root/'notification.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        return _notify(root,config,retry)


class Campaign:
    def __init__(self, root, config):
        self.root, self.config = root, config
        self.systems = selected_systems(config)
        self.completed = 0
        self.total = None
        self.latest = None

    def status(self, stage, **extra):
        state = dict(stage=stage, completed=self.completed, total=self.total,
                     latest=self.latest, artifact_paths=[str(self.root)], updated=time.time(), **extra)
        atomic(self.root/'status.json', state)
        with (self.root/'events.jsonl').open('a') as stream:
            stream.write(json.dumps(state, sort_keys=True)+'\n'); stream.flush(); os.fsync(stream.fileno())

    def stage(self, name, request):
        snapshot=self.root/'code.json'
        token = hashlib.sha256(json.dumps([name, request], sort_keys=True).encode()).hexdigest()[:20]
        directory = self.root/'stages'/token
        directory.mkdir(parents=True, exist_ok=True)
        receipt = directory/'receipt.json'
        if receipt.exists() and not invalidated(self.root, receipt):
            saved = read(receipt)
            if any(digest(path) != sha for path, sha in saved['hashes'].items()):
                raise ValueError('Completed stage evidence changed: '+str(directory))
            self.latest = next((p for p in saved['hashes'] if Path(p).name=='result.json'), self.latest)
            return saved['result']
        # Explicit resume preserves prior attempts, including incomplete hardware actions.
        attempt = directory/str(len(list(directory.glob('attempt-*')))+1)
        attempt = attempt.with_name('attempt-'+attempt.name)
        attempt.mkdir()
        request_path, result_path = attempt/'request.json', attempt/'result.json'
        atomic(request_path, dict(request, stage=name))
        self.status(name, request=str(request_path))
        argv = self.config['adapter']+[name, str(request_path), str(result_path)]
        with (attempt/'log.txt').open('w') as log:
            process = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=900 if name=='progress-plots' else self.config['deadlines'][name])
            except BaseException:
                os.killpg(process.pid, signal.SIGTERM)
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL); process.wait()
                raise
        if code:
            raise RuntimeError(f'{name} exited {code}; evidence: {attempt}')
        result = read(result_path)
        if result.get('passed') is not True:
            raise ValueError(f'{name} gate failed: {result}')
        files = result.get('files', {})
        if not files or any(not Path(p).is_absolute() or digest(p) != sha for p, sha in files.items()):
            raise ValueError('Adapter must hash absolute evidence paths')
        if name == 'loaded':
            if result.get('health') is not True or result.get('restored') is not True:
                raise ValueError('Loaded hardware health/restoration gate failed')
            if result.get('trace_readouts') != 0 or result.get('software_profiling') is not False or result.get('fpga_recording') is not False or result.get('nic_timestamping') is not False:
                raise ValueError('Loaded case instrumentation contract violated')
            if result.get('generator_cpus') != self.config['generator_cpus'] or result.get('flows') != self.config['flows']:
                raise ValueError('Generator placement/flow mismatch')
            from campaign.policy import validate_loaded_activity
            trial, _ = load(result['csv'], result['summary'], collect_latencies=False)
            validate_loaded_activity(result.get('worker_activity', []),request['workers'],trial)
            if (trial.get('schema')!=(3 if self.config.get('aggregate',False) else 2) or trial['benchmark']!='echo'+str(request['payload_bytes'])
                    or trial['warmup_ns']!=request['warmup_ms']*1_000_000):
                raise ValueError('Workload/warmup boundary provenance differs from plan')
            if trial['generator_cpus'] != self.config['generator_cpus']:
                raise ValueError('Actual generator CPU placement differs from manifest')
            if self.config.get('batch_max',1)>1:
                if (trial.get('arrival_model')!='paced-batch'
                        or trial.get('batch_max')!=self.config['batch_max']
                        or trial.get('batch_target_ns')!=self.config.get('batch_target_ns',250000)):
                    raise ValueError('Batch arrival configuration differs from plan')
            elif trial.get('arrival_model')=='paced-batch':
                raise ValueError('Unexpected batched arrivals')
            if trial['rate'] != request['rate'] or trial['duration_ns'] != request['duration_ms']*1_000_000:
                raise ValueError('Acquisition duration/rate differs from frozen plan')
            files.update(trial['files'])
            result['trial'] = trial
        if name == 'unloaded':
            cohort=result['cohort']
            from campaign.import_unloaded import validate_request
            validate_request(cohort,request)
            instrumented=request['instrumentation']=='breakdown'
            if (result.get('wire_available') is not True
                    or result.get('software_profiling') is not instrumented
                    or result.get('tracing_compiled') is not instrumented
                    or result.get('trace_readouts')!=0
                    or result.get('fpga_recording') is not False
                    or result.get('nic_timestamping') is not False):
                raise ValueError('Unloaded wire/software instrumentation contract violated')
            for gate in ('xid_pairing','health','restored'):
                if result.get(gate) is not True: raise ValueError('Unloaded gate missing: '+gate)
        if name == 'preflight':
            for gate in ('reservations', 'matching_artifacts', 'storage_budget', 'ram_budget',
                         'restoration', 'callback_delivery', 'isolated_cpus'):
                if result.get(gate) is not True:
                    raise ValueError('Preflight gate missing: '+gate)
            if result.get('initial_state')!='fresh' and result.get('preserved_previous_state') is not True:
                raise ValueError('Initial recorder preservation gate missing')
        if name == 'rehearsal-validation':
            gates=('unloaded_modes','cleanup','import','plots') if self.config.get('scope')=='unloaded-rehearsal' else ('unloaded_modes', 'loaded_sizes', 'overload_retained', 'scheduling', 'no_loaded_trace_reads', 'cleanup', 'import', 'plots')
            for gate in gates:
                if result.get(gate) is not True:
                    raise ValueError('Rehearsal gate missing: '+gate)
        hashes = dict(files, **{str(request_path): digest(request_path), str(result_path): digest(result_path)})
        atomic(receipt, dict(result=result, hashes=hashes,
                             code=read(snapshot) if snapshot.exists() else {}))
        self.latest = str(result_path)
        return result

    def loaded(self, system, size, workers, rate, phase, trial=0, sustained=False):
        duration = 60_000 if sustained else 10_000
        warmup = 2_000
        if phase == 'rehearsal': duration, warmup = 1000, 1000
        arrival = (dict(arrival_model='paced-batch', batch_max=self.config['batch_max'],
                        batch_target_ns=self.config.get('batch_target_ns',250000))
                   if self.config.get('batch_max',1)>1 else {})
        result = self.stage('loaded', dict(**arrival, system=system, payload_bytes=size, workers=workers,
            rate=rate, phase=phase, repetition=trial, duration_ms=duration, warmup_ms=warmup,
            deadline_ms=10, generator_cpus=self.config['generator_cpus'], flows=self.config['flows']))
        self.completed += 1
        self.status('acquisition')
        return result['trial']

    def unloaded(self, phase):
        cohorts=[]
        systems=tuple(s for s in ('DPDK','LH','CS-A64') if s in self.systems) if self.config.get('scope') in ('unloaded-rehearsal','unloaded-full') else self.systems
        for system in systems:
            for size in SIZES:
                for instrumentation in ('off', 'breakdown'):
                    result=self.stage('unloaded', dict(system=system, payload_bytes=size, workers=1,
                        outstanding=1, count=100 if phase=='rehearsal' else 10000,
                        warmup=128,
                        instrumentation=instrumentation, phase=phase))
                    cohorts.append(result['cohort'])
                    self.completed+=1
                    self.status('acquisition')
        validate_matched(cohorts, rehearsal=phase=='rehearsal', systems=self.systems)
        atomic(self.root/(phase+'-unloaded.json'),cohorts)

    def run(self):
        self.stage('preflight', {'checked_at': time.time_ns()})
        self.stage('build', {})
        if self.config.get('scope') == 'unloaded-full':
            self.total=len(self.systems)*len(SIZES)*2
            self.unloaded('full')
            result=self.stage('progress-plots',dict(unloaded=read(self.root/'full-unloaded.json'),final_unloaded=True))
            previews=result.get('previews',[])
            if len(previews)!=2 or any(p not in result['files'] for p in previews):
                raise ValueError('Full unloaded collection requires two validated plot previews')
            if result.get('complete') is not True: raise ValueError('Unloaded final validation missing')
            self.status('complete',previews=previews,scope='unloaded-full',loaded_collection_started=False)
            return
        if self.config.get('scope') == 'full':
            return self.collect_full()
        if self.config.get('scope') == 'unloaded-rehearsal':
            self.total=len(self.systems)*len(SIZES)*2
            self.unloaded('rehearsal')
            result=self.stage('rehearsal-validation',dict(unloaded_only=True))
            previews=result.get('previews',[])
            if len(previews)!=2 or any(p not in result['files'] for p in previews):
                raise ValueError('Unloaded rehearsal requires two hashed plot previews')
            self.status('rehearsal-complete',previews=previews,
                        scope='unloaded-rehearsal',full_collection_started=False)
            return
        # Before bracketing, total is the finite maximum cohort budget.
        self.total = len(self.systems)*len(SIZES)*2 + len(self.systems)*len(SIZES)*2*(self.config['max_extensions']+1)
        self.unloaded('rehearsal')
        boundaries = {}
        for system in self.systems:
            for size in SIZES:
                for workers in (1, 4):
                    rate = self.config['initial_rate']
                    for _ in range(self.config['max_extensions']):
                        trial = self.loaded(system, size, workers, rate, 'rehearsal')
                        if loss(trial) > LOSS_LIMIT: break
                        rate = next_rate(rate)
                    else: raise RuntimeError('No capacity boundary within rehearsal budget')
                    boundaries[f'{system}-{size}-{workers}'] = rate
                    # One valid point exceeding 1% establishes overload coverage.
        result = self.stage('rehearsal-validation', dict(boundaries=boundaries))
        if self.config.get('scope', 'rehearsal') == 'rehearsal':
            previews = result.get('previews', [])
            if len(previews) != 4 or any(p not in result['files'] for p in previews):
                raise ValueError('Rehearsal callback requires four hashed plot previews')
            self.total = self.completed
            self.status('rehearsal-complete', previews=previews,
                        scope='rehearsal', full_collection_started=False)
            return

    def collect_full(self):
        plan_path = self.root/'grid.json'
        plans = read(plan_path) if plan_path.exists() else self.config.get('grid')
        if plans is None and self.config.get('loaded_sweep') == 'linear-to-loss':
            plans = [dict(system=s, size=n, workers=w, rates=[5000 if s=='LH' else 20000],
                          step=5000 if s=='LH' else 20000, stop_loss=.01)
                     for s in self.systems for n in SIZES for w in WORKERS]
        expected={(s,n,w) for s in self.systems for n in SIZES for w in WORKERS}
        if not plans or len(plans)!=len(expected) or {(p['system'],p['size'],p['workers']) for p in plans}!=expected:
            raise ValueError('Full collection requires an explicit complete frozen grid')
        for p in plans:
            rates=p['rates']
            if 'step' in p and (type(p['step']) is not int or
                    p['step'] != (5000 if p['system']=='LH' else 20000) or
                    rates != [p['step']] or p.get('stop_loss') != LOSS_LIMIT):
                raise ValueError('Invalid linear-to-loss plan')
            if not rates or rates!=sorted(set(rates)) or any(type(r)!=int or r<=0 for r in rates):
                raise ValueError('Grid rates must be positive increasing integers')
        if not plan_path.exists(): atomic(plan_path,plans)
        self.completed = 0
        self.total = len(self.systems)*len(SIZES)*2+sum(len(p['rates'])+1 for p in plans)
        self.unloaded('full')
        from campaign.adapter import stop_after_unloaded
        stopping=stop_after_unloaded(self.root)
        result=self.stage('progress-plots', dict(unloaded=read(self.root/'full-unloaded.json'),
                                                **({'final_unloaded':True} if stopping else {})))
        if stop_after_unloaded(self.root):
            if result.get('complete') is not True:
                result=self.stage('progress-plots',dict(unloaded=read(self.root/'full-unloaded.json'),final_unloaded=True))
            previews=result.get('previews',[])
            if result.get('complete') is not True or len(previews)!=2 or any(p not in result['files'] for p in previews):
                raise ValueError('Requested stop requires validated full unloaded plots')
            self.total=self.completed
            self.status('complete',previews=previews,scope='unloaded-full',loaded_collection_started=False,
                        stop_reason='Requested stop after unloaded; loaded grid awaits revision')
            return
        collected = []
        for plan in plans:
            points = []
            def point(rate, sustained=False):
                return dict(rate=rate, trials=[self.loaded(plan['system'], plan['size'], plan['workers'],
                    rate, 'sustained' if sustained else 'full', t, sustained) for t in range(1)])
            def refresh():
                self.stage('progress-plots',dict(loaded=collected+[dict(plan,points=points)]))
            for rate in plan['rates']:
                points.append(point(rate)); refresh()
            # Linear plans terminate on observed loss; legacy frozen grids keep
            # their bounded geometric extensions. Persist intent before acquisition.
            extensions = 0
            while not any(loss(t)>LOSS_LIMIT for p in points for t in p['trials']):
                if 'step' not in plan and extensions >= self.config['max_extensions']: break
                rate = points[-1]['rate'] + plan['step'] if 'step' in plan else next_rate(points[-1]['rate'])
                extension_path=self.root/f"extensions-{plan['system']}-{plan['size']}-{plan['workers']}.json"
                rates=read(extension_path) if extension_path.exists() else []
                if rate not in rates: rates.append(rate); atomic(extension_path,rates)
                self.total += 1; points.append(point(rate)); refresh()
                extensions += 1
            if not any(loss(t)>LOSS_LIMIT for p in points for t in p['trials']):
                raise RuntimeError('No overload boundary within extension budget')
            confirmed = None
            confirmations = []
            for candidate in candidates(points):
                if confirmations: self.total += 1
                sustained = point(candidate['rate'], True); confirmations.append(sustained)
                if all(loss(t)<=LOSS_LIMIT for t in sustained['trials']):
                    confirmed = sustained; break
            if confirmed is None: raise RuntimeError('No sustained qualifying candidate')
            collected.append(dict(plan, points=points, confirmed=confirmed, confirmations=confirmations))
            atomic(self.root/'loaded.json', collected)
            self.stage('progress-plots',dict(loaded=collected))
        result = self.stage('finalize', dict(grid=str(plan_path), loaded=str(self.root/'loaded.json'), unloaded=str(self.root/'full-unloaded.json')))
        for gate in ('complete', 'hashes', 'boundaries', 'failure_accounting', 'worker_activity', 'pdf_dimensions', 'embedded_fonts'):
            if result.get(gate) is not True: raise ValueError('Final gate missing: '+gate)
        previews=result.get('previews', [])
        if len(previews)<5 or any(p not in result['files'] for p in previews):
            raise ValueError('Final callback requires five hashed plot previews')
        self.status('complete', previews=previews)


def config_check(config):
    selected_systems(config)
    if config.get('loaded_sweep') not in (None, 'linear-to-loss'):
        raise ValueError('Unknown loaded sweep policy')
    if config.get('loaded_sweep') and config.get('grid') is not None:
        raise ValueError('Choose loaded_sweep or an explicit frozen grid')
    from campaign.wire import require_capture_backend
    if config.get('scope') != 'unloaded-rehearsal': require_capture_backend(config)
    if config.get('scope', 'rehearsal') not in ('rehearsal', 'full', 'unloaded-rehearsal', 'unloaded-full'):
        raise ValueError('Scope must be unloaded-full, unloaded-rehearsal, rehearsal or full')
    if not 1 <= len(config['generator_cpus']) <= 32 or len(set(config['generator_cpus'])) != len(config['generator_cpus']):
        raise ValueError('Between one and 32 distinct isolated generator CPUs required')
    if not 1<=config.get('batch_max',1)<=64 or not 0<config.get('batch_target_ns',250000)<=1_000_000:
        raise ValueError('Invalid batching configuration')
    if type(config.get('aggregate',False)) is not bool or (config.get('aggregate',False) and config.get('batch_max',1)==1):
        raise ValueError('Aggregate observations require paced batches and a boolean flag')
    if config['flows'] < len(config['generator_cpus']) or config['initial_rate'] < 1 or not 1 <= config['max_extensions'] <= 30:
        raise ValueError('Invalid flow/rate/extension budget')
    if not config.get('thread') or not config.get('adapter'):
        raise ValueError('Thread and concrete acquisition adapter are required')
    for name in ('preflight','build','loaded','unloaded','rehearsal-validation','finalize'):
        if not 0 < config['deadlines'][name] <= 86400: raise ValueError('Invalid stage deadline')


def implementation(config):
    paths=[SELF,*sorted((SELF.parent/'campaign').glob('*.py')),
           SELF.parent/'paper_chart.py', SELF.parent/'plot_echo.py',
           SELF.parent/'plot_echo_unloaded.py',
           *(SELF.parent/name for name in ('transfer_results.py',)),
           *sorted((ROOT/'tools/enzian').glob('*.py')),
           *sorted((ROOT/'tools/enzian/trace').glob('*.py')),
           ROOT/'tools/enzian/trace/control.tcl', ROOT/'vivado/eci/program_fpga.tcl',
           ROOT/'data/eci/sys_trace/dump_trace_udp.py',
           ROOT/'data/eci/sys_trace/dump_trace_hw_axi.tcl', ROOT/'tools/enzian/cpu.sh',
           *(Path(p).resolve() for p in config['adapter'] if Path(p).is_file())]
    code={str(p):digest(p) for p in paths}
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('launch','run','status','notify','failed','recovery-plan','inheritance-plan'))
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--inherit-unloaded', type=Path)
    parser.add_argument('--inherit-reason')
    parser.add_argument('--inherit-reviewed', type=Path)
    parser.add_argument('--inherit-review', type=Path)
    parser.add_argument('--recovery-review', type=Path)
    args = parser.parse_args(); root = args.root.resolve()
    if args.command == 'inheritance-plan':
        from campaign.recovery import inheritance_draft
        config=read(args.config);config_check(config)
        print(json.dumps(inheritance_draft(args.inherit_reviewed,config,implementation(config)),indent=2))
        return
    if args.command == 'recovery-plan':
        from campaign.recovery import draft
        print(json.dumps(draft(root,implementation(read(root/'config.json'))),indent=2))
        return
    if args.command == 'status': print(json.dumps(read(root/'status.json'), indent=2)); return
    if args.command == 'launch':
        config = read(args.config); config_check(config)
        if args.recovery_review and not args.resume: raise ValueError('Recovery requires --resume')
        if root.exists() and not args.resume: raise ValueError('Campaign root already exists')
        if bool(args.inherit_reviewed)!=bool(args.inherit_review): raise ValueError('Reviewed inheritance requires source and review')
        if args.inherit_reviewed and args.inherit_unloaded: raise ValueError('Choose one inheritance mode')
        if args.inherit_reviewed and (root/'code.json').exists(): raise ValueError('Reviewed inheritance requires a new root')
        if args.inherit_unloaded and (root/'code.json').exists(): raise ValueError('Inheritance requires a new campaign root')
        root.mkdir(parents=True, exist_ok=args.resume)
        if (root/'config.json').exists() and read(root/'config.json') != config: raise ValueError('Incompatible resume configuration')
        # Controller code is hashed too; resume cannot silently change semantics.
        code=implementation(config)
        with (ROOT/'out/echo-campaign.lock').open('a') as global_lock, (root/'lock').open('a') as lock:
            fcntl.flock(global_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            if args.inherit_reviewed:
                from campaign.recovery import inherit_reviewed
                inherit_reviewed(args.inherit_reviewed,root,config,code,read(args.inherit_review),atomic)
            atomic(root/'config.json', config)
            if args.recovery_review:
                prepare_recovery(root,code,read(args.recovery_review),atomic)
            else:
                atomic(root/'code.json',code)
            if args.inherit_unloaded:
                from campaign.recovery import inherit_unloaded
                inherit_unloaded(args.inherit_unloaded,root,args.inherit_reason,atomic)
        unit='echo-campaign-'+hashlib.sha256(str(root).encode()).hexdigest()[:12]
        hook=' '.join('"'+str(x).replace('\\','\\\\').replace('"','\\"')+'"' for x in [sys.executable,SELF,'failed','--root',root])
        atomic(root/'status.json',dict(stage='queued',completed=0,total=0,artifact_paths=[str(root)]))
        environment=['--setenv='+key+'='+os.environ[key] for key in
                     ('PATH','PYTHONPATH','NIX_PYTHONPATH','NIX_SSL_CERT_FILE','SSL_CERT_FILE',
                      'ENZIAN_BMC_PASSWORD','ENZIAN_PASSWORD','SSH_AUTH_SOCK') if key in os.environ]
        subprocess.run(['systemd-run','--user','--collect','--unit',unit,
            '--property=Type=exec','--property=KillMode=control-group',
            '--property=TimeoutStopSec=60','--property=ExecStopPost='+hook,
            '--working-directory='+str(ROOT),*environment,sys.executable,str(SELF),'run','--root',str(root)], check=True)
        print(json.dumps(dict(launched=True, unit=unit, status=str(root/'status.json')))); return
    config=read(root/'config.json'); config_check(config)
    if args.command=='notify': notify(root,config); return
    if args.command=='failed':
        if not (root/'status.json').exists() or read(root/'status.json')['stage'] not in ('failed','complete','rehearsal-complete'):
            previous=read(root/'status.json') if (root/'status.json').exists() else {}
            atomic(root/'status.json',dict(previous,stage='failed',failed_stage=previous.get('stage'),
                error='Controller terminated unexpectedly',artifact_paths=[str(root)]))
        notify(root,config); return
    with (ROOT/'out/echo-campaign.lock').open('a') as global_lock, (root/'lock').open('a') as lock:
        fcntl.flock(global_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        campaign=Campaign(root,config)
        try: campaign.run()
        except BaseException as error:
            failed_stage=read(root/'status.json').get('stage')
            campaign.status('failed',failed_stage=failed_stage,error=str(error)); notify(root,config); raise
        notify(root,config)


if __name__ == '__main__': main()
