"""Matched Enzian acquisition for the echo campaign controller.

All CPU access and cold boots use the repository harness. Loaded acquisition
freezes recording; full trace preservation is only for exceptional boot/module
failures. Unloaded totals await external Tofino integration.
"""
import csv
import hashlib
import gzip
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import shlex
import shutil
import sys

from .acquire import ROOT, roles, remote_script, validate_role
from .harness import Harness
from .import_loaded import digest
from .import_unloaded import dpdk, lauberhorn


def atomic(path, data):
    temporary=path.with_suffix('.tmp');temporary.write_text(json.dumps(data,indent=2)+'\n');temporary.replace(path)


class Acquisition:
    def __init__(self, config, request, directory, campaign):
        self.config=config;self.request=request;self.directory=Path(directory);self.campaign=Path(campaign)
        self.timings=[]
        self.h=Harness(config,directory)
        self.label='echo-'+hashlib.sha256(str(self.campaign).encode()).hexdigest()[:8]+'-'+digest(self.directory/'request.json')[:12]+'-'+self.directory.name
        self.state_path=self.campaign/'hardware-state.json'
        self.state=json.loads(self.state_path.read_text()) if self.state_path.exists() else {}

    def timed(self, stage, function, *args, **kwargs):
        started=time.monotonic()
        passed=False
        try:
            result=function(*args,**kwargs)
            passed=True
            return result
        finally:
            self.timings.append(dict(stage=stage,seconds=time.monotonic()-started,passed=passed))
            atomic(self.directory/'timings.json',self.timings)

    def role(self, role, *, setup=False):
        r=self.request;c=self.config;loaded=r['stage']=='loaded' and not setup
        server=role=='server';system=r['system']
        source=c['dut_endpoint'] if server else c['peer_endpoint']
        destination=c['peer_endpoint'] if server else c['dut_endpoint'] if system=='DPDK' else c['fpga_endpoint']
        label=self.label+('-setup' if setup else '')
        return dict(role=role,system=system,mode='loaded' if loaded else 'unloaded',
            cpus=c['worker_cpus'][:r['workers']] if server else c['generator_cpus'] if loaded else c['generator_cpus'][:1],
            output='/dev/shm/'+label+'-'+role,label=label,transfer_url=c['transfer_url'],
            binary='/nix/store/lauberhorn-client/bin/'+(('echo_dpdk' if r.get('instrumentation')=='breakdown' else 'echo_dpdk_uninstrumented') if server else 'bench_dpdk'),
            manifest='/nix/store/lauberhorn-client/manifest.json',
            interface=c.get('dut_interface','enP5p144s0np0') if server else c.get('peer_interface','cpu40g1'),
            endpoint=[source['ip'],destination['ip'],destination['mac'],49152 if server else 12345],
            source_cidr=source['ip']+'/'+str(source['prefix']),
            deadline_ms=1000 if server and not loaded and c.get('tofino') else 10,flows=c['flows'] if loaded else 1,
            payload_bytes=r['payload_bytes'],count=16 if setup else r.get('count',1),warmup=0 if setup else r.get('warmup',0),
            aggregate=c.get('aggregate',False),batch_max=c.get('batch_max',1),batch_target_ns=c.get('batch_target_ns',250000),
            rate=r.get('rate',0),duration_ms=r.get('duration_ms',0),warmup_ms=r.get('warmup_ms',0),
            instrumentation='off' if setup else r.get('instrumentation','off'),
            tracking_mib=c['tracking_mib'],timeout_seconds=900,
            gap_ns=0 if setup or loaded else c.get('tofino',{}).get('gap_ns',0))

    def execute_roles(self, capture=None):
        configs={'client':(self.config['peer'],self.role('client'))}
        if self.request['system']=='DPDK': configs={'server':(self.config['dpdk_dut'],self.role('server')),**configs}
        common=dict(gateway=self.config['gateway'], transfer_root=self.config['transfer_root'],
                    image=self.config['client_image_remote'], image_sha=self.config['client_image_sha256'])
        if capture is None:
            paths=self.timed('roles',roles,self.directory/'roles',configs,**common)
        else:
            # Start the ordinary responder first; only the client belongs inside
            # the switch session's readiness/finish window.
            client_spec=self.directory/'tofino-generator.json'
            client_result=self.directory/'tofino-generator-result.json'
            client_config=configs['client'][1]
            client_config['timeout_seconds']=120
            atomic(client_spec,dict(roles=dict(directory=str((self.directory/'client-roles').resolve()),
                configs={'client':configs['client']},**common),
                ledger={k:self.request[k] for k in ('count','warmup','payload_bytes')},
                result=str(client_result.resolve())))
            with ThreadPoolExecutor(max_workers=1) as pool:
                server=None
                if 'server' in configs:
                    server=pool.submit(roles,self.directory/'server-roles',{'server':configs['server']},**common)
                    ready=self.config['transfer_root']+'/'+self.label+'-ready'
                    wait_code=("import pathlib,time\np=pathlib.Path("+repr(ready)+")\n"
                        "end=time.monotonic()+120\n"
                        "while not p.exists():\n"
                        " if time.monotonic()>end:raise TimeoutError('Responder readiness')\n"
                        " time.sleep(.1)\n")
                    self.h.gateway(['python3','-c',wait_code],'resident-responder-ready',timeout=150)
                self.paired=self.timed('tofino-session',capture.run,
                    [sys.executable,'-m','tools.paper.campaign.tofino',str(client_spec.resolve())])
                paths=json.loads(client_result.read_text())
                if server is not None: paths.update(server.result())
        evidence={}
        for role,(_,request) in configs.items():
            evidence[role]=validate_role(paths[role],request,self.config['server_sha256'][self.request.get('instrumentation','off')] if role=='server' else self.config['client_sha256'])
        def cleanup(item):
            role,(machine,request)=item
            harness=Harness(self.config,self.directory/('cleanup-'+role))
            harness.directory.mkdir()
            # Only remove this role's temporary RAM files after the complete
            # local archive and every uploaded member have been verified.
            root=request['output']
            code=f'''from pathlib import Path
import shutil
root=Path({root!r})
if root.parent!=Path('/dev/shm') or not root.name.startswith('echo-'):raise ValueError('Unexpected cleanup root')
shutil.rmtree(root)
root.with_suffix('.tar.gz').unlink()
'''
            harness.python(machine,code,'cleanup-'+role)
            harness.gateway(['rm','--',self.config['transfer_root']+'/'+Path(root).name+'.tar.gz'],'cleanup-upload-'+role)
        # Separate hosts and harness directories; wait for every cleanup, including failures.
        with ThreadPoolExecutor(max_workers=len(configs)) as pool:
            futures=[pool.submit(cleanup,item) for item in configs.items()]
            errors=[]
            for future in futures:
                try: future.result()
                except Exception as error: errors.append(error)
            if errors: raise RuntimeError('Role cleanup failed: '+ '; '.join(map(str,errors)))
        if self.request['stage']=='loaded':
            raw=Path(paths['client'])/'samples.csv';compressed=raw.with_suffix('.csv.gz')
            with raw.open('rb') as source, compressed.open('xb') as destination:
                with gzip.GzipFile(filename='',mode='wb',fileobj=destination,mtime=0,compresslevel=1) as stream:
                    shutil.copyfileobj(source,stream,1024*1024)
            evidence['client']['files'][str(compressed.resolve())]=digest(compressed)
            evidence['client']['files'].pop(str(raw.resolve()))
            raw.unlink()
        return {k:Path(v) for k,v in paths.items()},evidence

    def ensure_lh(self):
        r=self.request;c=self.config
        key={k:r.get(k) for k in ('payload_bytes','workers','stage','instrumentation')}
        if self.state.get('safe') is False:
            raise RuntimeError('Previous hardware stage failed or was interrupted; inspect preserved evidence before recovery')
        reuse=r['stage']=='loaded' and self.state.get('service_key')==key
        if reuse: return self.state['service']
        # A fresh boot ends the old worker pool; core reclamation is deferred.
        variant=c['native_variants'][r.get('instrumentation','off')]
        service=dict(image=c['client_image_remote'],image_sha256=c['client_image_sha256'],
            service_root='/dev/shm/'+self.label+'-service',binary_path=variant['app']+'/bin/microbenchmarks',
            binary_sha256=variant['sha256'],runtime=variant['runtime'],
            tracing_compiled=r.get('instrumentation')=='breakdown',payload_bytes=r['payload_bytes'],
            workers=r['workers'],mode='loaded' if r['stage']=='loaded' else 'unloaded',
            instrumentation=r.get('instrumentation','off'))
        helper=Path(__file__).with_name('dut.py');url=self.h.stage(helper,self.label+'-dut.py')
        case=json.loads(Path(c['boot_case']).read_text())[0]
        case['name']=self.label
        case['program']=[str(x).replace('program-hot-uninstrumented.log','program-'+self.label+'.log') for x in case['program']]
        endpoint=c['fpga_endpoint'];peer=c['peer_endpoint']
        code=f'''import hashlib,urllib.request
data=urllib.request.urlopen({url!r},timeout=30).read()
if hashlib.sha256(data).hexdigest()!={digest(helper)!r}:raise ValueError('DUT helper mismatch')
namespace={{'__name__':'campaign_dut'}};exec(compile(data,'dut.py','exec'),namespace)
namespace['start']({service!r})
'''
        case['cpu']='\n'.join(['set -eu','ip link set cpu40g1 down',
            shlex.join(['cpu','configure',endpoint['mac'],endpoint['ip']+'/'+str(endpoint['prefix'])]),
            shlex.join(['ip','neigh','replace',peer['ip'],'lladdr',peer['mac'],'nud','permanent','dev','lauberhorn0']),
            'mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing',
            "printf '0\\n' > /sys/kernel/tracing/tracing_on",'systemctl start rpcbind',
            "python3 - <<'ECHOSTART'",self.h.image_code(),code,'ECHOSTART'])
        setup=self.role('client',setup=True)
        priming=dict(setup,count=1,deadline_ms=1000,purpose='neighbor-priming',
                     label=self.label+'-neighbor',output='/dev/shm/'+self.label+'-neighbor-client')
        peer_script=Path(__file__).with_name('peer.py');peer_url=self.h.stage(peer_script,self.label+'-peer.py')
        case['client_machine']=c['peer']
        scripts=[]
        for name,role in (('neighbor',priming),('setup',setup)):
            path=self.directory/(name+'-request.json');atomic(path,role)
            url=self.h.stage(path,self.label+'-'+name+'.json')
            scripts.append(remote_script(url,peer_url,digest(peer_script),c['client_image_remote'],c['client_image_sha256']))
        # The first reply populates the FPGA neighbor cache through the normal
        # kernel miss path. This isolated priming call precedes all setup,
        # warmup and measured requests; only priming permits a longer deadline.
        case['client']='set -eu\n'+'\n'.join(scripts)
        cases=self.directory/'cases.json';atomic(cases,[case])
        atomic(self.state_path,dict(safe=False,stage=str(self.directory),service_key=key,service=service))
        try:
            self.h.command([sys.executable,ROOT/'tools/enzian/test.py',cases,'--gateway',c['gateway'],
                            '--machine',c['dut'],'--logs',self.directory/'board','--repeats','1'],
                            'board',timeout=7200)
        except Exception:
            # Preserve both preparation roles even when the harness exits early.
            for role in (priming,setup):
                archive=self.directory/(Path(role['output']).name+'.tar.gz')
                self.h.command(['rsync',c['gateway']+':'+c['transfer_root']+'/'+archive.name,archive],
                               'failed-setup-evidence',check=False)
            self.capture(exceptional=True)
            raise
        for role in (priming,setup):
            archive=self.directory/(Path(role['output']).name+'.tar.gz')
            self.h.command(['rsync',c['gateway']+':'+c['transfer_root']+'/'+archive.name,archive],'setup-evidence')
            from .acquire import extract
            unpacked=extract(archive,self.directory/(Path(role['output']).name+'-extracted'),Path(role['output']).name)
            validate_role(unpacked,role,c['client_sha256'])
        self.state=dict(safe=True,service_key=key,service=service,setup_count=17,priming_count=1,
                        board=str(self.directory/'board/summary.json'))
        atomic(self.state_path,self.state)
        return service

    def ensure_dpdk(self):
        c=self.config
        machine=c['dpdk_dut']
        if machine!='zuestoll07' or c['dut_interface']!='enP5p144s0np0':
            raise ValueError('ConnectX-5 baseline requires the verified zuestoll07 interface')
        if self.state.get('system')=='DPDK': return
        atomic(self.state_path,dict(safe=False,stage=str(self.directory),system='DPDK'))
        # CPU-NIC baseline needs no FPGA programming, module or trace capture.
        self.h.command([sys.executable,ROOT/'tools/enzian/boot.py','--gateway',c['gateway'],
            '--machine',machine,'--owner',c['owner'],'--logs',self.directory/'dpdk-boot',
            '--negative-no-bitstream'],'dpdk-boot',timeout=3600)
        body='set -eu\n'
        body+='test "$(basename "$(readlink -f /sys/class/net/'+c['dut_interface']+'/device/driver)")" = mlx5_core\n'
        body+="python3 - <<'ECHOIMAGE'\n"+self.h.image_code()+"\nECHOIMAGE\n"
        self.h.cpu(machine,body,'dpdk-prepare',timeout=600)
        self.state=dict(safe=True,system='DPDK',boot=str(self.directory/'dpdk-boot/summary.json'))
        self.last_recorder=None
        atomic(self.state_path,self.state)

    def capture(self, exceptional=False):
        c=self.config;label=self.label+('-failure' if exceptional else '')
        result=self.h.capture(label)
        self.last_recorder=result['restored_recorder']
        return Path(result['raw'])

    def freeze_loaded(self):
        """Reuse a verified freeze only within the same detached service invocation.

        Boot paths replace self.state. Failed runs remain unsafe. A service restart,
        reviewed inheritance or configuration change therefore rechecks the hardware.
        """
        identity=dict(invocation=os.environ.get('INVOCATION_ID'),
                      config=hashlib.sha256(json.dumps(self.config,sort_keys=True).encode()).hexdigest())
        saved=self.state.get('loaded_freeze',{})
        receipt=Path(saved.get('receipt','/nonexistent'))
        if (identity['invocation'] and saved.get('identity')==identity
                and receipt.is_file() and digest(receipt)==saved.get('sha256')):
            values=json.loads(receipt.read_text())
            if values.get('trace_stop')!=1: raise ValueError('Invalid frozen recorder receipt')
            self.last_recorder=values
            atomic(self.directory/'freeze-reused.json',saved)
            return
        self.last_recorder=self.h.vio('configure',self.label,self.directory/(self.label+'-freeze.log'))
        receipt=self.directory/'loaded-freeze.json'
        atomic(receipt,self.last_recorder)
        self.state['loaded_freeze']=dict(identity=identity,receipt=str(receipt.resolve()),sha256=digest(receipt))

    def run(self):
        r=self.request;c=self.config;loaded=r['stage']=='loaded'
        if not loaded and c.get('tofino'):
            from .tofino import check_backend
            check_backend(c['tofino'])
            if r['count']+r['warmup']>1024:
                raise ValueError('Unloaded Tofino rehearsal must fit one capture chunk')
        if self.state.get('safe') is False: raise RuntimeError('Hardware state needs explicit failure recovery')
        if r['system']=='DPDK': self.timed('ensure-dpdk',self.ensure_dpdk)
        service=self.timed('ensure-lh',self.ensure_lh) if r['system']=='LH' else None
        atomic(self.state_path,dict(self.state,safe=False,stage=str(self.directory)))
        before=self.timed('snapshot-before',self.h.snapshot,service,self.label+'-before') if service else None
        self.last_recorder=None
        frozen=service is not None
        capture=None
        try:
            if frozen: self.timed('freeze-recording',self.freeze_loaded)
            if not loaded and c.get('tofino'):
                from .tofino import Batch
                backend=c['tofino']
                if r['count']+r['warmup']>1024:
                    raise ValueError('Unloaded Tofino rehearsal must fit one capture chunk')
                endpoint=c['dut_endpoint'] if r['system']=='DPDK' else c['fpga_endpoint']
                selector=backend['rpc_selectors'][str(r['payload_bytes'])]
                capture=Batch(backend,dict(session_id=self.label,dev_port=backend['ports'][r['system']],
                    client_ip=c['peer_endpoint']['ip'],server_ip=endpoint['ip'],
                    client_udp_port=49152,server_udp_port=12345,**selector,
                    expected_count=r['count']+r['warmup'],timeout_s=600,
                    pacing_gap_ns=backend['gap_ns'],exclusive_tx_fifo_ack=backend['exclusive_tx_fifo_ack'],
                    expected_artifact_sha256=backend['expected_artifact_sha256']),
                    self.directory/'tofino')
            paths,evidence=self.timed('roles-and-cleanup',self.execute_roles,capture)
            if capture: paired=self.paired
            after=self.timed('snapshot-after',self.h.snapshot,service,self.label+'-after',export=r.get('instrumentation')=='breakdown') if service else None
            if service:
                self.h.command([sys.executable,ROOT/'tools/enzian/assess_tad.py',
                    '--before',before/'tad.txt','--after',after/'tad.txt',
                    '--kernel-before',before/'kernel.txt','--kernel-after',after/'kernel.txt',
                    '--output',self.directory/'health.json'],'health')
                if not json.loads((self.directory/'health.json').read_text())['passed']:
                    raise ValueError('DUT hardware health failed')
            result=dict(passed=True,health=True,restored=True,files={},nic_timestamping=False)
            for role in evidence: result['files'].update(evidence[role]['files'])
            if loaded:
                if service:
                    a=json.loads((before/'provenance.json').read_text())['activity']
                    b=json.loads((after/'provenance.json').read_text())['activity']
                    if len(a)!=r['workers'] or len(b)!=len(a) or any(x['calls'] and (x['tid']!=y['tid'] or x['cpu']!=y['cpu']) for x,y in zip(a,b)):
                        raise ValueError('Resident worker identity changed')
                    active=[w for w in b if w['calls']>0]
                    if (any(w['calls']<0 or (not w['calls'] and (w['cpu'] or w['tid'])) for w in b)
                            or len({w['cpu'] for w in active})!=len(active)
                            or not {w['cpu'] for w in active}<=set(c['worker_cpus'])):
                        raise ValueError('Resident worker placement differs')
                    activity=[y['calls']-x['calls'] for x,y in zip(a,b)]
                else: activity=evidence['server']['worker_activity']
                result.update(csv=str(paths['client']/'samples.csv.gz'),summary=str(paths['client']/'samples.csv.open.json'),
                    generator_cpus=c['generator_cpus'],flows=c['flows'],worker_activity=activity,
                    trace_readouts=0,software_profiling=False,fpga_recording=False)
            else:
                options={k:r[k] for k in ('count','warmup','instrumentation','payload_bytes')}
                if service:
                    cohort=lauberhorn(paths['client']/'samples.csv',
                        setup_count=self.state['setup_count'],
                        server_path=after/'server.csv' if r['instrumentation']=='breakdown' else None,
                        **options)
                else:
                    cohort=dpdk(paths['client']/'samples.csv',paths['server']/'samples.csv',**options)
                if capture:
                    # The backend currently exports unqualified MAC ticks. Keep
                    # them alongside CPU durations; never fabricate ns/residuals.
                    cohort['raw_capture']=dict(path=str(capture.output/'pairs.json'),
                        sha256=digest(capture.output/'pairs.json'),
                        units=paired['units'],hardware_timing_validated=paired['hardware_timing_validated'])
                    for row,raw in zip(cohort['rows'],paired['pairs']):
                        if row['request_id']!=raw['xid'] or bool(row['warmup'])!=raw['warmup']:
                            raise ValueError('Capture/software XID or warmup mismatch')
                        row['switch_delta_raw']=raw['delta_raw']
                    result['files'].update({str(p):digest(p) for p in capture.output.iterdir() if p.is_file()})
                cohort['build_config_sha256']=c['build_config_sha256'][r['system']][r['instrumentation']]
                result.update(cohort=cohort,xid_pairing=True,wire_available=False,
                              tracing_compiled=r['instrumentation']=='breakdown',
                              software_profiling=r['instrumentation']=='breakdown',
                              trace_readouts=0,fpga_recording=False,nic_timestamping=False)
            return result
        finally:
            if 'result' in locals() and result.get('passed'):
                # Result creation alone is not success: retain unsafe state if
                # any later validation raised before the return above.
                if sys.exc_info()[0] is None:
                    atomic(self.state_path,dict(self.state,safe=True,recorder=self.last_recorder,
                                               last_result=str(self.directory/'result.json')))
