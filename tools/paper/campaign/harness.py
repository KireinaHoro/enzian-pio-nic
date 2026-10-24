"""Campaign transport primitives; provisioning remains exclusively EMG-owned."""
import json
from pathlib import Path
import shlex
import subprocess
import sys

from .acquire import ROOT, extract
from .import_loaded import digest


class Harness:
    def __init__(self, config, directory):
        self.config=config;self.directory=Path(directory)
        self.index=0

    def command(self, argv, name, timeout=180, check=True):
        self.index+=1
        path=self.directory/f'{self.index:03d}-{name}.log'
        with path.open('w') as stream:
            result=subprocess.run(list(map(str,argv)),stdout=stream,stderr=subprocess.STDOUT,timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f'{name} exited {result.returncode}; preserved at {path}')
        return result.returncode

    def program_command(self):
        """Stage the canonical programmer; never execute remote-only wrappers."""
        c=self.config
        source=ROOT/'vivado/eci/program_fpga.tcl'
        sha=digest(source)
        remote=c['fpga_directory']+'/program-'+sha+'.tcl'
        self.command(['rsync',source,c['programmer']+':'+remote],'stage-programmer')
        (self.directory/'programmer-source.tcl').write_bytes(source.read_bytes())
        return ['ssh',c['programmer'],c['vivado'],'-mode','batch','-nojournal',
                '-log',c['fpga_directory']+'/program-'+self.directory.parent.name+'-'+self.directory.name+'.log',
                '-source',remote,'-tclargs',c['gateway']+':3121',c['cable'],
                c['fpga_directory']+'/shell_lauberhorn-eci.bit',
                c['fpga_directory']+'/shell_lauberhorn-eci.ltx']

    def gateway(self, argv, name, **kwargs):
        return self.command(['ssh',self.config['gateway'],'cd /tmp && '+shlex.join(list(map(str,argv)))],name,**kwargs)

    def stage(self, source, name=None):
        source=Path(source);name=name or source.name
        target=self.config['transfer_root']+'/'+name
        self.command(['rsync',str(source),self.config['gateway']+':'+target],'stage-'+name,timeout=600)
        return self.config['transfer_url'].rstrip('/')+'/'+name

    def cpu(self, machine, body, name, timeout=180):
        script=self.directory/(name+'.sh');script.write_text(body)
        return self.command([sys.executable,ROOT/'tools/enzian/run.py','--gateway',self.config['gateway'],
            '--machine',machine,'--logs',self.directory/(name+'-console'),'--timeout',timeout,
            '--script',script],name,timeout=timeout+180)

    def python(self, machine, code, name, timeout=180):
        return self.cpu(machine,"python3 - <<'ECHOPROBE'\n"+code+"\nECHOPROBE\n",name,timeout)

    def image_code(self):
        c=self.config
        return f'''import hashlib,os,urllib.request
from pathlib import Path
def sha(path):
 value=hashlib.sha256()
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
 return value.hexdigest()
image=Path({c['client_image_remote']!r})
if image.exists():
 if sha(image)!={c['client_image_sha256']!r}:raise ValueError('Existing image differs; retain it for diagnosis')
else:
 temporary=image.with_name(image.name+'.partial-'+str(os.getpid()))
 with urllib.request.urlopen({c['client_image_url']!r},timeout=120) as source,temporary.open('xb') as target:
  while True:
   block=source.read(1024*1024)
   if not block:break
   target.write(block)
 if sha(temporary)!={c['client_image_sha256']!r}:raise ValueError('Image transfer hash mismatch')
 temporary.rename(image)
'''

    def probe(self, machine, name):
        """Read-only host state, uploaded through the dedicated campaign endpoint."""
        remote=name+'.json';url=self.config['transfer_url'].rstrip('/')+'/'+remote
        image=self.config.get('deploy_image') if machine==self.config['dut'] else None
        interface=self.config.get('dut_interface','enP5p144s0np0') if machine==self.config.get('dpdk_dut') else 'cpu40g1'
        code=f'''import json,os,subprocess,urllib.request,hashlib
from pathlib import Path
mem={{k:int(v.split()[0])*1024 for k,v in (line.split(':',1) for line in Path('/proc/meminfo').read_text().splitlines())}}
def stat(path):
 s=os.statvfs(path);return dict(available=s.f_bavail*s.f_frsize,total=s.f_blocks*s.f_frsize)
result=dict(kernel=subprocess.check_output(['uname','-r'],text=True).strip(),
 isolated=Path('/sys/devices/system/cpu/isolated').read_text().strip(),
 nohz_full=Path('/sys/devices/system/cpu/nohz_full').read_text().strip(),
 mem_available=mem['MemAvailable'],shm=stat('/dev/shm'),
 command_line=Path('/proc/cmdline').read_text().strip(),
 management_route=subprocess.check_output(['ip','route','get','192.168.255.254'],text=True),
 nic=json.loads(subprocess.check_output(['ip','-j','addr','show','dev',{interface!r}])),
 nic_driver=(Path('/sys/class/net')/{interface!r}/'device/driver').resolve().name,
 nic_pci=(Path('/sys/class/net')/{interface!r}/'device').resolve().name,
 root=stat('/'))
image={image!r}
if image:
 sha=hashlib.sha256()
 with open(image,'rb') as source:
  for block in iter(lambda:source.read(1024*1024),b''):sha.update(block)
 result['deployment_sha256']=sha.hexdigest()
request=urllib.request.Request({url!r},data=json.dumps(result).encode(),method='PUT')
with urllib.request.urlopen(request,timeout=30) as response:response.read()
'''
        self.python(machine,code,name)
        target=self.directory/remote
        self.command(['rsync',self.config['gateway']+':'+self.config['transfer_root']+'/'+remote,target],'fetch-'+name)
        return json.loads(target.read_text())

    def snapshot(self, service, name, export=False):
        module=Path(__file__).with_name('dut.py')
        url=self.stage(module,'dut-'+digest(module)[:12]+'.py')
        peer=Path(__file__).with_name('peer.py')
        peer_url=self.stage(peer,'peer-'+digest(peer)[:12]+'.py')
        output='/dev/shm/'+name
        # Only the upload helper is used; importing the role module does not run a role.
        code=f'''import hashlib,urllib.request
def module(url,expected):
 data=urllib.request.urlopen(url,timeout=30).read()
 if hashlib.sha256(data).hexdigest()!=expected: raise ValueError('Helper hash mismatch')
 namespace={{'__name__':'campaign_helper'}};exec(compile(data,url,'exec'),namespace);return namespace
dut=module({url!r},{digest(module)!r})
peer=module({peer_url!r},{digest(peer)!r})
dut['snapshot']({service!r},{output!r},export={export!r})
peer['upload'](dut['Path']({output!r}),{self.config['transfer_url']!r})
'''
        self.python(self.config['dut'],code,name,timeout=180)
        archive=self.directory/(name+'.tar.gz')
        self.command(['rsync',self.config['gateway']+':'+self.config['transfer_root']+'/'+archive.name,archive],'fetch-'+name)
        return extract(archive,self.directory/(name+'-extracted'),name)

    def trace_config(self):
        if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
        from tools.enzian.trace.control import campaign_config
        return campaign_config(self.config)

    def vio(self, mode, label, output):
        # Recording-only controls share canonical snapshots, without trace reads.
        config=self.trace_config()
        from tools.enzian.trace.control import Control
        mode='freeze' if mode=='configure' else mode
        return Control(config,self.directory/('trace-control-'+label)).call(mode,output)

    def capture(self, label):
        config=self.trace_config()
        from tools.enzian.trace.session import capture
        return capture(config,self.directory/(label+'-dump'))
