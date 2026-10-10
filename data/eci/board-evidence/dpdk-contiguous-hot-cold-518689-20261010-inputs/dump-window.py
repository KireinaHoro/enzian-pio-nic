import pathlib,subprocess,sys,re,json,hashlib,time
b=pathlib.Path('out/nullrpc-contiguous');label=sys.argv[1]
text=(b/(label+'-window.log')).read_text()
def fields(prefix):return {k:int(v,16) for k,v in re.findall(r'^'+prefix+r' (\w+) ([0-9a-fA-F]+)$',text,re.M)}
a,z=fields('WINDOW_START'),fields('WINDOW_END')
start=a['trace_write_slot_1']*64;end=z['trace_write_slot_1']*64
if end<=start or a['trace_wrapped_1']!=z['trace_wrapped_1']:raise RuntimeError('window crossed ring boundary; preserve full ring')
if a['trace_dma_error_1'] or z['trace_dma_error_1']:raise RuntimeError('DMA error')
size=end-start;work=b/(label+'-dump');work.mkdir()
remote='/dev/shm/nullrpc-contiguous-'+label
shared='/scratch/pengxu/nullrpc-contiguous/'+label+'-receive.sh'
def run(argv,path,timeout=180):
 with path.open('w') as f:subprocess.run(argv,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
def vio(mode):
 run(['ssh','enzian-ba2','/opt/Xilinx/Vivado/2023.2/bin/vivado -mode batch -nolog -nojournal -source /tmp/lauberhorn-518689/capture-vio.tcl -tclargs '+mode+' nullrpc-'+label],work/(mode+'.log'))
vio('configure')
script=work/'receive.sh'
script.write_text(f'''set -eu
export PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin
unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME
mkdir {remote}
printf ready > /scratch/pengxu/nullrpc-contiguous/{label}-receiver-ready
lh-trace-dump --bind-ip 192.168.128.225 --offset {start} --bytes {size} --wait-timeout 90 --timeout 1 --retries 5 --chunk-bytes 1408 --out {remote}/trace-dram.bin > {remote}/receiver.stdout 2> {remote}/receiver.stderr
sha256sum {remote}/trace-dram.bin > {remote}/SHA256SUMS
''')
run(['rsync',str(script),'enzian-gateway:'+shared],work/'stage.log')
f=(work/'receiver-run.log').open('w')
p=subprocess.Popen([sys.executable,'tools/enzian/run.py','--machine','zuestoll07','--logs',str(work/'peer'),'--timeout','1200','--','bash',shared],stdout=f,stderr=subprocess.STDOUT)
for i in range(90):
 r=subprocess.run(['ssh','enzian-gateway','test -f /scratch/pengxu/nullrpc-contiguous/'+label+'-receiver-ready'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 if r.returncode==0:break
 if p.poll() is not None:raise RuntimeError('receiver exited before readiness')
 time.sleep(1)
else:raise RuntimeError('receiver not ready')
vio('trigger');print('DUMP_STARTED',size,flush=True)
if p.wait(timeout=1250):raise RuntimeError('receiver failed; preserve partial trace')
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
s=http.server.HTTPServer(('192.168.192.7',18869),Handler);s.timeout=90
s.handle_request();s.server_close()
''')
export='/scratch/pengxu/nullrpc-contiguous/'+label+'-export.py'
run(['rsync',str(serve),'enzian-gateway:'+export],work/'stage-export.log')
f=(work/'export-run.log').open('w')
p=subprocess.Popen([sys.executable,'tools/enzian/run.py','--machine','zuestoll07','--logs',str(work/'export-peer'),'--timeout','240','--','/nix/store/lauberhorn-client/bin/python3',export],stdout=f,stderr=subprocess.STDOUT)
(work/'trace').mkdir()
# curl retries connection refusal only, before any transfer has started.
c=subprocess.Popen(['ssh','enzian-gateway','curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 http://192.168.192.7:18869/capture'],stdout=subprocess.PIPE)
t=subprocess.run(['tar','-xf','-','-C',str(work/'trace')],stdin=c.stdout,timeout=240);c.stdout.close()
if t.returncode or c.wait(timeout=10) or p.wait(timeout=30):raise RuntimeError('export failed; RAM capture preserved')
raw=work/'trace/trace-dram.bin'
with raw.open('rb') as f:
 h=hashlib.sha256()
 for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
digest=h.hexdigest()
assert raw.stat().st_size==size and digest==(work/'trace/SHA256SUMS').read_text().split()[0]
result=dict(bytes=size,sha256=digest,offset=start,start=a,end=z,bounded_window=True,pipeline=518689)
(work/'summary.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
