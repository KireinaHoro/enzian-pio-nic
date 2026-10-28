"""Repository-owned Tcl, persistent control snapshots, and bounded JTAG checks."""
import contextlib
import fcntl
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import shlex
import subprocess


ROOT=Path(__file__).resolve().parents[3]


def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()


def atomic(path,value):
    tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(value,indent=2)+'\n');tmp.replace(path)


def campaign_config(c):
    """Adapt explicit campaign endpoints; persist every resolved value."""
    if c['pipeline']!=519555:raise ValueError('Supply an explicit trace config for a different topology')
    artifacts={a['path']:a['sha256'] for a in c['remote_artifacts'] if a['host']==c['programmer'] and a['path'].endswith(('.bit','.ltx'))}
    return dict(gateway=c['gateway'],programmer=c['programmer'],dut=c['dut'],peer=c['peer'],
        vivado=c['vivado'],hw_server=c['gateway']+':3121',cable=c['cable'],
        ltx=c['fpga_directory']+'/shell_lauberhorn-eci.ltx',remote_artifacts=artifacts,
        pipeline=c['pipeline'],trace_map=c['trace_map'],trace_map_sha256=digest(c['trace_map']),
        peer_ip=c['peer_endpoint']['ip'],peer_mac=c['peer_endpoint']['mac'],
        peer_management_ip=c['peer_management_ip'],export_port=18874,
        peer_image=c['client_image_remote'],peer_image_sha256=c['client_image_sha256'],
        transfer_root=c['transfer_root'],transfer_url=c['transfer_url'])


class Control:
    def __init__(self,config,directory):
        self.config=config;self.directory=Path(directory).resolve()
        self.directory.mkdir(parents=True,exist_ok=True)
        self.remote='/tmp/lauberhorn-trace-'+hashlib.sha256(str(self.directory).encode()).hexdigest()[:20]
        path=self.directory/'config.json'
        if path.exists() and json.loads(path.read_text())!=config:raise ValueError('Incompatible trace session')
        if not path.exists():atomic(path,config)

    def command(self,argv,log,timeout=180):
        with Path(log).open('x') as stream:
            return subprocess.run(list(map(str,argv)),stdout=stream,stderr=subprocess.STDOUT,timeout=timeout,check=True)

    def setup(self):
        if (self.directory/'sources.json').exists():
            sources=json.loads((self.directory/'sources.json').read_text())
            if any(digest(p)!=sha for p,sha in sources.items()):raise ValueError('Trace implementation changed during session')
            return
        c=self.config
        if c['ltx'] not in c['remote_artifacts'] or not any(p.endswith('.bit') for p in c['remote_artifacts']):
            raise ValueError('Matched bitstream and LTX hashes are required')
        from tools.enzian.console import require_reservation
        for machine in (c['dut'],c['peer']):require_reservation(c['gateway'],machine)
        if digest(c['trace_map'])!=c['trace_map_sha256']:raise ValueError('Trace map hash mismatch')
        mapping=json.loads(Path(c['trace_map']).read_text())
        if any(src.get('autopipeline') for src in mapping.get('sources', [])):
            if mapping.get('pipeline_resolution')!='implemented':
                raise ValueError('Trace auto-pipeline latency is unresolved')
            bits={sha for path,sha in c['remote_artifacts'].items() if path.endswith('.bit')}
            if mapping.get('implementation',{}).get('bitstream_sha256') not in bits:
                raise ValueError('Implemented trace map does not match the bitstream')
        for path,sha in c['remote_artifacts'].items():
            actual=subprocess.check_output(['ssh',c['programmer'],shlex.join(['sha256sum',path])],text=True,timeout=120).split()[0]
            if actual!=sha:raise ValueError('Remote artifact hash mismatch: '+path)
        self.command(['ssh',c['programmer'],shlex.join(['mkdir',self.remote])],self.directory/'stage-directory.log')
        sources=[Path(__file__).with_name('control.tcl'),ROOT/'tools/trace/eci/sys_trace/dump_trace_hw_axi.tcl']
        self.command(['rsync',*map(str,sources),c['programmer']+':'+self.remote+'/'],self.directory/'stage-source.log')
        evidence=self.directory/'implementation';evidence.mkdir()
        implementation=[ROOT/'tools/enzian/image.py',*sorted(Path(__file__).parent.glob('*.py')),*sources,ROOT/'tools/trace/eci/sys_trace/dump_trace_udp.py']
        for source in implementation:(evidence/source.name).write_bytes(source.read_bytes())
        atomic(self.directory/'sources.json',{str(p):digest(p) for p in sources})
        # Snapshot matching map locally so captures remain interpretable after cleanup.
        (self.directory/'trace-map.json').write_bytes(Path(c['trace_map']).read_bytes())

    @contextlib.contextmanager
    def exclusive(self):
        if getattr(self,'locked',False):
            yield
            return
        key=hashlib.sha256((self.config['programmer']+self.config['cable']).encode()).hexdigest()[:16]
        directory=ROOT/'out';directory.mkdir(exist_ok=True)
        with (directory/('trace-'+key+'.lock')).open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.locked=True
            try:yield
            finally:self.locked=False

    def call(self,mode,output=None,size=0):
        with self.exclusive():return self._call(mode,output,size)

    def _call(self,mode,output=None,size=0):
        self.setup();c=self.config
        if bool(c.get('trace_ip')) != bool(c.get('trace_mac')):
            raise ValueError('Supply both trace_ip and trace_mac for a trace identity override')
        if c.get('trace_mac') and not re.fullmatch(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}',c['trace_mac']):
            raise ValueError('Invalid trace MAC address')
        argv=[c['vivado'],'-mode','batch','-nolog','-nojournal','-source',self.remote+'/control.tcl','-tclargs',mode,
              c['hw_server'],c['cable'],c['ltx'],self.remote+'/snapshot.txt',
              ipaddress.IPv4Address(c['peer_ip']).packed.hex(),c['peer_mac'].replace(':',''),
              self.remote+'/dump_trace_hw_axi.tcl',self.remote+'/head.bin',self.remote+'/tail.bin',str(size),
              ipaddress.IPv4Address(c['trace_ip']).packed.hex() if c.get('trace_ip') else '-',
              c.get('trace_mac','-').replace(':','')]
        attempts=[]
        for attempt in range(3):
            log=self.directory/f'operation-{len(list(self.directory.glob("operation-*.log")))+1:03d}-{mode}.log'
            with log.open('x') as f:
                result=subprocess.run(['ssh',c['programmer'],shlex.join(argv)],stdout=f,stderr=subprocess.STDOUT,timeout=300)
            text=log.read_text()
            attempts.append(dict(log=str(log),returncode=result.returncode))
            atomic(self.directory/'last-operation.json',dict(mode=mode,attempts=attempts))
            if output:Path(output).write_text(text)
            if result.returncode==0:break
            # Retry the same idempotent operation, including crashes before
            # completion. Ordinary Tcl, SSH and timeout failures remain fatal.
            if result.returncode not in (139,-11) or re.search(r'^ERROR:',text,re.M) or attempt==2:
                raise RuntimeError(f'Trace {mode} failed ({result.returncode}); evidence: {log}')
        values={k:int(v,16) for k,v in re.findall(r'^DIAG vio_output \S*/(\w+) ([0-9a-fA-F]+)$',text,re.M)}
        values['_status']={k:int(v,16) for k,v in re.findall(r'^TRACE_STATUS (\w+) ([0-9a-fA-F]+)$',text,re.M)}
        if mode in ('configure','freeze','preserve') and values.get('trace_stop')!=1:raise ValueError('Recorder did not freeze')
        if mode in ('configure','freeze'):
            self.command(['rsync',c['programmer']+':'+self.remote+'/snapshot.txt',str(self.directory/'snapshot.txt')],self.directory/'snapshot-copy.log')
        atomic(self.directory/'state.json',dict(mode=mode,values=values,log=str(log)))
        return values

    def check(self,raw):
        size=Path(raw).stat().st_size
        sample=min(size,4096)
        self.call('check',size=size)
        for part in ('head','tail'):
            self.command(['rsync',self.config['programmer']+':'+self.remote+'/'+part+'.bin',self.directory/(part+'.bin')],self.directory/(part+'-copy.log'))
        with Path(raw).open('rb') as f:
            head=f.read(sample);f.seek(size-sample);tail=f.read(sample)
        result=dict(head_matches=head==(self.directory/'head.bin').read_bytes(),tail_matches=tail==(self.directory/'tail.bin').read_bytes(),sample_bytes=sample)
        atomic(self.directory/'integrity.json',result)
        if not all(result[k] for k in ('head_matches','tail_matches')):raise ValueError('JTAG integrity mismatch')
        return result
