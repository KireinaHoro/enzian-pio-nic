"""Bounded CMAC collection using the existing dump_trace_udp receiver."""
import hashlib
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile
import time

from .control import ROOT, digest
from tools.enzian.image import downloaded_python, PYTHON


def extract(stream,directory,size):
    expected={'trace-dram.bin','SHA256SUMS','receiver.stdout','receiver.stderr'}
    seen=set()
    with tarfile.open(fileobj=stream,mode='r|') as archive:
        for entry in archive:
            if entry.name not in expected or entry.name in seen or not entry.isfile():
                raise ValueError('Unexpected capture archive member')
            limit=size if entry.name=='trace-dram.bin' else 16*1024*1024
            if entry.size>limit:raise ValueError('Oversized capture archive member')
            seen.add(entry.name)
            with (directory/entry.name).open('xb') as f:shutil.copyfileobj(archive.extractfile(entry),f)
    if seen!=expected:raise ValueError('Incomplete capture archive')


class Transport:
    def __init__(self,control):
        self.control=control;self.config=control.config;self.root=control.directory
        self.label=self.root.name+'-'+hashlib.sha256(str(self.root).encode()).hexdigest()[:12]
        self.remote='/dev/shm/'+self.label
        self.index=0

    def stage(self,path):
        c=self.config
        self.control.command(['rsync',path,c['gateway']+':'+c['transfer_root']+'/'+path.name],self.root/('stage-'+path.name+'.log'))
        return c['transfer_url'].rstrip('/')+'/'+path.name

    def start(self,script,kind,timeout):
        url=self.stage(script);c=self.config
        log=(self.root/(kind+'-run.log')).open('x')
        launcher=self.root/(kind+'-launch.sh')
        launcher.write_text(downloaded_python(c['peer_image'],c['peer_image_sha256'],url,digest(script),
            "exec(compile(script,'trace-role.py','exec'))\n"))
        process=subprocess.Popen([sys.executable,str(ROOT/'tools/enzian/run.py'),'--gateway',c['gateway'],'--machine',c['peer'],
            '--logs',str(self.root/(kind+'-console')),'--timeout',str(timeout),'--script',str(launcher)],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        log.close();return process

    def receive(self,size):
        c=self.config;reader=ROOT/'data/eci/sys_trace/dump_trace_udp.py'
        local=self.root/(self.label+'-dump_trace_udp.py');local.write_bytes(reader.read_bytes());url=self.stage(local)
        ready=self.label+'-ready';ready_url=c['transfer_url'].rstrip('/')+'/'+ready
        timeout=max(1200,180+(size+4_000_000-1)//4_000_000)
        script=self.root/(self.label+'-receive.py')
        script.write_text(f'''import hashlib,subprocess,urllib.request,time
from pathlib import Path
root=Path({self.remote!r});root.mkdir()
data=urllib.request.urlopen({url!r},timeout=30).read()
assert hashlib.sha256(data).hexdigest()=={digest(reader)!r}
reader=root/'dump_trace_udp.py';reader.write_bytes(data)
with (root/'receiver.stdout').open('w') as out,(root/'receiver.stderr').open('w') as err:
 p=subprocess.Popen([{PYTHON!r},str(reader),'--bind-ip',{c['peer_ip']!r},'--offset','0','--bytes',{str(size)!r},'--wait-timeout','90','--timeout','1','--retries','5','--chunk-bytes','1408','--ready-file',str(root/'ready'),'--out',str(root/'trace-dram.bin')],stdout=out,stderr=err)
 try:
  deadline=time.monotonic()+30
  while not (root/'ready').exists():
   if p.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Receiver failed before socket readiness')
   time.sleep(.05)
  urllib.request.urlopen(urllib.request.Request({ready_url!r},data=b'bound',method='PUT'),timeout=30).read()
  if p.wait(timeout={timeout}):raise RuntimeError('CMAC receiver failed; preserve partial files')
 finally:
  if p.poll() is None:p.terminate();p.wait(timeout=10)
h=hashlib.sha256()
with (root/'trace-dram.bin').open('rb') as f:
 for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
(root/'SHA256SUMS').write_text(h.hexdigest()+'  trace-dram.bin\\n')
''')
        process=self.start(script,'receiver',timeout+60)
        try:
            for _ in range(90):
                check=subprocess.run(['ssh',c['gateway'],shlex.join(['test','-f',c['transfer_root']+'/'+ready])],timeout=10,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                if check.returncode==0:break
                if process.poll() is not None:raise RuntimeError('Receiver exited before readiness')
                time.sleep(1)
            else:raise RuntimeError('Receiver readiness deadline exceeded')
            self.control.call('trigger')
            if process.wait(timeout=timeout+90):raise RuntimeError('CMAC receiver failed; remote partial evidence retained')
        except BaseException:
            # Allow the receiver's own metadata/data deadline to preserve output.
            # The remote wrapper also has a terminal timeout; never restart it.
            if process.poll() is None:
                import os,signal
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
            raise

    def export(self,size):
        c=self.config;timeout=max(240,120+(size+8_000_000-1)//8_000_000)
        attempt=len(list(self.root.glob(self.label+'-export-*.py')))+1
        kind='export-'+str(attempt)
        script=self.root/(self.label+'-'+kind+'.py')
        script.write_text(f'''import http.server,tarfile,os
os.chdir({self.remote!r})
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path!='/capture':self.send_error(404);return
  self.send_response(200);self.end_headers()
  with tarfile.open(fileobj=self.wfile,mode='w|') as tar:
   for name in ('trace-dram.bin','SHA256SUMS','receiver.stdout','receiver.stderr'):tar.add(name,arcname=name)
s=http.server.HTTPServer(({c['peer_management_ip']!r},{c['export_port']}),Handler);s.timeout=90
s.handle_request();s.server_close()
''')
        process=self.start(script,kind,timeout)
        target=self.root/'trace'
        if target.exists():target.rename(self.root/('trace-partial-'+str(attempt)))
        target.mkdir()
        url=f"http://{c['peer_management_ip']}:{c['export_port']}/capture"
        # Persist the transfer as a partial archive before parsing it. This also
        # bounds the network phase and retains malformed/partial export evidence.
        archive=self.root/('capture-'+str(attempt)+'.tar.partial')
        with archive.open('xb') as f,(self.root/(kind+'-download.log')).open('x') as log:
            subprocess.run(['ssh',c['gateway'],shlex.join(['curl','--fail','--silent','--show-error','--max-time',str(timeout),'--retry','30','--retry-connrefused','--retry-delay','1',url])],stdout=f,stderr=log,check=True,timeout=timeout+60)
        if process.wait(timeout=30):raise RuntimeError('Capture export server failed')
        with archive.open('rb') as f:extract(f,target,size)
        raw=target/'trace-dram.bin'
        if raw.stat().st_size!=size or digest(raw)!=(target/'SHA256SUMS').read_text().split()[0]:raise ValueError('Capture size/hash mismatch')
        archive.unlink() # Verified raw data now persisted; failures retain partial tar.
        return raw
