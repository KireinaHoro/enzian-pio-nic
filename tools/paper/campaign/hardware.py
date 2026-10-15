"""Matched Enzian acquisition for the echo campaign controller.

All CPU access and cold boots use the repository harness. Loaded acquisition
only freezes recording; full trace preservation belongs to unloaded cohorts
and exceptional boot/module failures.
"""
import csv
import hashlib
import gzip
import json
from pathlib import Path
import shlex
import shutil
import sys

from .acquire import ROOT, roles, remote_script, validate_role
from .harness import Harness
from .import_loaded import digest
from .import_unloaded import dpdk, lauberhorn, uninstrumented


def atomic(path, data):
    temporary=path.with_suffix('.tmp');temporary.write_text(json.dumps(data,indent=2)+'\n');temporary.replace(path)


class Acquisition:
    def __init__(self, config, request, directory, campaign):
        self.config=config;self.request=request;self.directory=Path(directory);self.campaign=Path(campaign)
        self.h=Harness(config,directory)
        self.label='echo-'+hashlib.sha256(str(self.campaign).encode()).hexdigest()[:8]+'-'+digest(self.directory/'request.json')[:12]+'-'+self.directory.name
        self.state_path=self.campaign/'hardware-state.json'
        self.state=json.loads(self.state_path.read_text()) if self.state_path.exists() else {}

    def role(self, role, *, setup=False):
        r=self.request;c=self.config;loaded=r['stage']=='loaded' and not setup
        server=role=='server';system=r['system']
        source=c['dut_endpoint'] if server else c['peer_endpoint']
        destination=c['peer_endpoint'] if server else c['dut_endpoint'] if system=='DPDK' else c['fpga_endpoint']
        label=self.label+('-setup' if setup else '')
        return dict(role=role,system=system,mode='loaded' if loaded else 'unloaded',
            cpus=c['worker_cpus'][:r['workers']] if server else c['generator_cpus'] if loaded else c['generator_cpus'][:1],
            output='/dev/shm/'+label+'-'+role,label=label,transfer_url=c['transfer_url'],
            binary='/nix/store/lauberhorn-client/bin/'+(('nullrpc_dpdk' if r.get('instrumentation')=='breakdown' else 'nullrpc_dpdk_uninstrumented') if server else 'bench_dpdk'),
            manifest='/nix/store/lauberhorn-client/manifest.json',ptp_bgx=c['ptp_bgx'],
            endpoint=[source['ip'],destination['ip'],destination['mac'],49152 if server else 12345],
            source_cidr=source['ip']+'/'+str(source['prefix']),deadline_ms=10,flows=c['flows'],
            payload_bytes=r['payload_bytes'],count=16 if setup else r.get('count',1),warmup=0 if setup else r.get('warmup',0),
            rate=r.get('rate',0),duration_ms=r.get('duration_ms',0),warmup_ms=r.get('warmup_ms',0),
            instrumentation='off' if setup else r.get('instrumentation','off'),
            tracking_mib=c['tracking_mib'],timeout_seconds=900)

    def execute_roles(self):
        configs={'client':(self.config['peer'],self.role('client'))}
        if self.request['system']=='DPDK': configs={'server':(self.config['dut'],self.role('server')),**configs}
        paths=roles(self.directory/'roles',configs,gateway=self.config['gateway'],
            transfer_root=self.config['transfer_root'],image=self.config['client_image_remote'],
            image_sha=self.config['client_image_sha256'])
        evidence={}
        for role,(_,request) in configs.items():
            evidence[role]=validate_role(paths[role],request,self.config['server_sha256'][self.request.get('instrumentation','off')] if role=='server' else self.config['client_sha256'])
        for role,(machine,request) in configs.items():
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
            self.h.python(machine,code,'cleanup-'+role)
            self.h.gateway(['rm','--',self.config['transfer_root']+'/'+Path(root).name+'.tar.gz'],'cleanup-upload-'+role)
        if self.request['stage']=='loaded':
            raw=Path(paths['client'])/'samples.csv';compressed=raw.with_suffix('.csv.gz')
            with raw.open('rb') as source, compressed.open('xb') as destination:
                with gzip.GzipFile(filename='',mode='wb',fileobj=destination,mtime=0) as stream:
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
        if self.state.get('system')=='DPDK': return
        c=self.config;case=json.loads(Path(c['boot_case']).read_text())[0]
        program=[str(x).replace('program-hot-uninstrumented.log','program-'+self.label+'.log') for x in case['program']]
        atomic(self.state_path,dict(safe=False,stage=str(self.directory),system='DPDK'))
        try:
            self.h.command([sys.executable,ROOT/'tools/enzian/boot.py','--gateway',c['gateway'],
                '--machine',c['dut'],'--owner',c['owner'],'--logs',self.directory/'dpdk-boot','--',*program],
                'dpdk-boot',timeout=3600)
        except Exception:
            self.capture(exceptional=True)
            raise
        # Booting ends the native worker pool. No Lauberhorn application or
        # module is started for the CPU-NIC baseline; boot.py validates ECI.
        body='set -eu\nip link set cpu40g1 down\n'
        body+='mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing\n'
        body+="printf '0\\n' > /sys/kernel/tracing/tracing_on\n"
        body+="python3 - <<'ECHOIMAGE'\n"+self.h.image_code()+"\nECHOIMAGE\n"
        self.h.cpu(c['dut'],body,'dpdk-prepare',timeout=600)
        self.state=dict(safe=True,system='DPDK',boot=str(self.directory/'dpdk-boot/summary.json'))
        atomic(self.state_path,self.state)

    def capture(self, exceptional=False):
        c=self.config;label=self.label+('-failure' if exceptional else '')
        result=self.h.capture(label)
        self.last_recorder=result['restored_recorder']
        return Path(result['raw'])

    def run(self):
        r=self.request;c=self.config;loaded=r['stage']=='loaded'
        if self.state.get('safe') is False: raise RuntimeError('Hardware state needs explicit failure recovery')
        if r['system']=='DPDK': self.ensure_dpdk()
        service=self.ensure_lh() if r['system']=='LH' else None
        atomic(self.state_path,dict(self.state,safe=False,stage=str(self.directory)))
        before=self.h.snapshot(service,self.label+'-before') if service else None
        frozen=loaded or r['system']=='DPDK' or r.get('instrumentation')=='off'
        try:
            if frozen: self.h.vio('configure',self.label,self.directory/(self.label+'-freeze.log'))
            paths,evidence=self.execute_roles()
            after=self.h.snapshot(service,self.label+'-after',export=r.get('instrumentation')=='breakdown') if service else None
            if service:
                self.h.command([sys.executable,ROOT/'tools/enzian/assess_tad.py',
                    '--before',before/'tad.txt','--after',after/'tad.txt',
                    '--kernel-before',before/'kernel.txt','--kernel-after',after/'kernel.txt',
                    '--output',self.directory/'health.json'],'health')
                if not json.loads((self.directory/'health.json').read_text())['passed']:
                    raise ValueError('DUT hardware health failed')
            result=dict(passed=True,health=True,restored=True,files={})
            for role in evidence: result['files'].update(evidence[role]['files'])
            if loaded:
                if service:
                    a=json.loads((before/'provenance.json').read_text())['activity']
                    b=json.loads((after/'provenance.json').read_text())['activity']
                    if len(a)!=r['workers'] or len(b)!=len(a) or any(x['calls'] and (x['tid']!=y['tid'] or x['cpu']!=y['cpu']) for x,y in zip(a,b)):
                        raise ValueError('Resident worker identity changed')
                    if len({w['cpu'] for w in b})!=r['workers'] or not {w['cpu'] for w in b}<=set(c['worker_cpus']):
                        raise ValueError('Resident worker placement differs')
                    activity=[y['calls']-x['calls'] for x,y in zip(a,b)]
                else: activity=evidence['server']['worker_activity']
                result.update(csv=str(paths['client']/'samples.csv.gz'),summary=str(paths['client']/'samples.csv.open.json'),
                    generator_cpus=c['generator_cpus'],flows=c['flows'],worker_activity=activity,
                    trace_readouts=0,software_profiling=False,fpga_recording=False)
            else:
                options={k:r[k] for k in ('count','warmup','instrumentation','payload_bytes')}
                if r['instrumentation']=='off':
                    cohort=uninstrumented(paths['client']/'samples.csv',system=r['system'],**options)
                    result.update(trace_readouts=0,software_profiling=False,fpga_recording=False,
                                  mac_timestamping=False,tracing_compiled=False)
                elif service:
                    trace=self.capture()
                    cohort=self.import_lh(trace,paths['client']/'samples.csv',after,options)
                    result['jtag_checks']=True
                else:
                    cohort=dpdk(paths['client']/'samples.csv',paths['server']/'samples.csv',**options)
                    result['timestamp_checks']=True
                cohort['build_config_sha256']=c['build_config_sha256'][r['system']][r['instrumentation']]
                result.update(cohort=cohort,xid_pairing=True)
                if r['instrumentation']=='breakdown':
                    result.update(mac_pairs=r['count']+r['warmup'],stored_loss=0)
            return result
        finally:
            if frozen: self.last_recorder=self.h.vio('preserve',self.label,self.directory/(self.label+'-restore.log'))
            if 'result' in locals() and result.get('passed'):
                # Result creation alone is not success: retain unsafe state if
                # any later validation raised before the return above.
                if sys.exc_info()[0] is None:
                    atomic(self.state_path,dict(self.state,safe=True,recorder=self.last_recorder,
                                               last_result=str(self.directory/'result.json')))

    def import_lh(self, trace, client, server, options):
        import import_nullrpc_mac as mac
        import numpy as np
        c=self.config;data=self.directory/'import';(data/'acquisition').mkdir(parents=True)
        check=data/'acquisition'/f'{self.label}-trace-check.json'
        self.h.command([sys.executable,ROOT/'tools/paper/check_nullrpc_capture.py',trace,'--map',c['trace_map'],
            '--expected-rpcs',self.state['setup_count']+options['count']+options['warmup'],'--output',check],'trace-check')
        stages={3,4,9,44,45,2,5,6}
        mac.extract(trace,Path(c['trace_map']),data=data,campaign=self.label,events=mac.EVENTS|stages)
        pairs=mac.correlate(data=data,campaign=self.label)
        mapping=json.loads(Path(c['trace_map']).read_text());sources={s['source']:s for s in mapping['sources']}
        events={k:[] for k in stages}
        with gzip.open(data/(self.label+'-mac-events.bin.gz'),'rb') as stream:
            raw=np.frombuffer(stream.read(),dtype='<u8').reshape(-1,2)
        for low,high in raw:
            low,high=int(low),int(high);eid=low&63;source=sources[(high>>11)&127]
            if eid in events: events[eid].append(((high>>18)-source['pipeline_stages'])*5)
        for values in events.values(): values.sort()
        cohort=lauberhorn(client,pairs,setup_count=self.state['setup_count'],
            priming_count=self.state.get('priming_count',0),
            server_path=server/'server.csv' if options['instrumentation']=='breakdown' else None,
            app_events=events,**options)
        cohort['files'].update({str(p):digest(p) for p in data.rglob('*') if p.is_file()})
        cohort['files'][str(trace)]=digest(trace)
        return cohort
