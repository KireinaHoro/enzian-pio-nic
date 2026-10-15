#!/usr/bin/env python3
import pathlib,subprocess,sys,re,json,hashlib,time
import argparse
parser=argparse.ArgumentParser(description='Preserve a full frozen, unwrapped paper trace over F_MAC1.')
parser.add_argument('--root',type=pathlib.Path,required=True)
parser.add_argument('--label',required=True)
parser.add_argument('--pipeline',type=int,required=True)
parser.add_argument('--transfer-root',required=True)
parser.add_argument('--transfer-url',default='http://192.168.255.254:18843')
a=parser.parse_args()
if not a.label.replace('-','').replace('_','').isalnum():raise ValueError('unsafe label')
b=a.root;label=a.label;pipeline=a.pipeline;transfer_root=a.transfer_root;transfer_url=a.transfer_url.rstrip('/')
artifacts=f'/tmp/lauberhorn-{pipeline}'
text=(b/(label+'-freeze.log')).read_text()
def fields(prefix):return {k:int(v,16) for k,v in re.findall(r'^'+prefix+r' (\w+) ([0-9a-fA-F]+)$',text,re.M)}
a,z=fields('BEFORE_FREEZE'),fields('TRACE_STATUS')
assert not z['trace_wrapped_1'],'wrapped buffer requires ring-aware capture'
assert not z['trace_dma_error_1'],'trace DMA error'
start=0;end=z['trace_write_slot_1']*64;size=end
# Large loopback bodies generate more ECI samples than null-RPC. Keep transfer
# time bounded, but scale the budget with the complete frozen byte count.
receive_timeout=max(1200,180+(size+4_000_000-1)//4_000_000)
export_timeout=max(240,120+(size+8_000_000-1)//8_000_000)
work=b/(label+'-dump');work.mkdir()
remote=f'/dev/shm/trace-recorder-{pipeline}-'+label
shared=transfer_root+'/'+label+'-receive.sh'
def run(argv,path,timeout=180):
 with path.open('w') as f:subprocess.run(argv,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
def vio(mode):
 run(['ssh','enzian-ba2','/opt/Xilinx/Vivado/2023.2/bin/vivado -mode batch -nolog -nojournal -source '+artifacts+'/capture-vio.tcl -tclargs '+mode+' paper-'+label],work/(mode+'.log'))
# The matching freeze/configure step is preserved in freeze.log.
script=work/'receive.sh'
script.write_text(f'''set -eu
export PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin
unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME
mkdir {remote}
python3 -c 'import urllib.request;urllib.request.urlopen(urllib.request.Request("{transfer_url}/{label}-receiver-ready",data=b"ready",method="PUT")).read()'
lh-trace-dump --bind-ip 192.168.128.225 --offset {start} --bytes {size} --wait-timeout 90 --timeout 1 --retries 5 --chunk-bytes 1408 --out {remote}/trace-dram.bin > {remote}/receiver.stdout 2> {remote}/receiver.stderr
sha256sum {remote}/trace-dram.bin > {remote}/SHA256SUMS
''')
run(['rsync',str(script),'enzian-gateway:'+shared],work/'stage.log')
f=(work/'receiver-run.log').open('w')
p=subprocess.Popen([sys.executable,'tools/enzian/run.py','--machine','zuestoll07','--logs',str(work/'peer'),'--timeout',str(receive_timeout),'--','/nix/store/lauberhorn-client/bin/python3','-c',f"import urllib.request,subprocess;subprocess.run(['bash','-c',urllib.request.urlopen('{transfer_url}/{label}-receive.sh').read().decode()],check=True)"],stdout=f,stderr=subprocess.STDOUT)
for i in range(90):
 r=subprocess.run(['ssh','enzian-gateway','test -f '+transfer_root+'/'+label+'-receiver-ready'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 if r.returncode==0:break
 if p.poll() is not None:raise RuntimeError('receiver exited before readiness')
 time.sleep(1)
else:raise RuntimeError('receiver not ready')
vio('trigger');print('DUMP_STARTED',size,flush=True)
if p.wait(timeout=receive_timeout+50):raise RuntimeError('receiver failed; preserve partial trace')
# Export only this capture, with one bounded HTTP request; no scratch copy.
serve=work/'export.py'
serve.write_text('''import http.server,tarfile,os
os.chdir('''+repr(remote)+''')
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path!='/capture':self.send_error(404);return
  self.send_response(200);self.end_headers()
  with tarfile.open(fileobj=self.wfile,mode='w|') as tar:
   for name in ('trace-dram.bin','SHA256SUMS','receiver.stdout','receiver.stderr'):tar.add(name,arcname=name)
s=http.server.HTTPServer(('192.168.192.7',18874),Handler);s.timeout=90
s.handle_request();s.server_close()
''')
export=transfer_root+'/'+label+'-export.py'
run(['rsync',str(serve),'enzian-gateway:'+export],work/'stage-export.log')
f=(work/'export-run.log').open('w')
p=subprocess.Popen([sys.executable,'tools/enzian/run.py','--machine','zuestoll07','--logs',str(work/'export-peer'),'--timeout',str(export_timeout),'--','/nix/store/lauberhorn-client/bin/python3','-c',f"import urllib.request;exec(urllib.request.urlopen('{transfer_url}/{label}-export.py').read())"],stdout=f,stderr=subprocess.STDOUT)
(work/'trace').mkdir()
# curl retries connection refusal only, before any transfer has started.
c=subprocess.Popen(['ssh','enzian-gateway','curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 http://192.168.192.7:18874/capture'],stdout=subprocess.PIPE)
t=subprocess.run(['tar','-xf','-','-C',str(work/'trace')],stdin=c.stdout,timeout=export_timeout);c.stdout.close()
if t.returncode or c.wait(timeout=10) or p.wait(timeout=30):raise RuntimeError('export failed; RAM capture preserved')
raw=work/'trace/trace-dram.bin'
with raw.open('rb') as f:
 h=hashlib.sha256()
 for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
digest=h.hexdigest()
assert raw.stat().st_size==size and digest==(work/'trace/SHA256SUMS').read_text().split()[0]
result=dict(bytes=size,sha256=digest,offset=start,start=a,end=z,bounded_window=True,pipeline=pipeline)
(work/'summary.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
