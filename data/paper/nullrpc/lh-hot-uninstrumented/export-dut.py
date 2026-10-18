import pathlib,subprocess,json,hashlib,tarfile,io,urllib.request
P=pathlib.Path
root=P('/dev/shm/paper-hot-uninstrumented-20261014')
procs=[]
for p in P('/proc').iterdir():
 if not p.name.isdigit():continue
 try:
  cmd=(p/'cmdline').read_bytes().split(b'\0')
  if not cmd or b'microbenchmarks' not in cmd[0]:continue
  env=dict(x.split('=',1) for x in (p/'environ').read_bytes().decode().split('\0') if '=' in x)
  procs.append(dict(pid=int(p.name),argv=[x.decode() for x in cmd if x],trace_env={k:v for k,v in env.items() if 'TRACE' in k or 'PMU' in k},binary_sha256=hashlib.sha256((p/'exe').read_bytes()).hexdigest()))
 except FileNotFoundError:continue
assert len(procs)==1,procs
assert procs[0]['argv'][-2:]==['add','1'],procs
assert not any(v for k,v in procs[0]['trace_env'].items() if k in ('LAUBERHORN_SERVER_TRACE_CSV','ADDER_SERVER_TRACE_CSV','LAUBERHORN_EVENT_TRACE_CSV','LAUBERHORN_PMU')),procs
r=dict(processes=procs,manifest=json.loads(P('/nix/store/lauberhorn/manifest.json').read_text()),kernel=subprocess.check_output(['uname','-r'],text=True).strip(),kernel_tracing=P('/sys/kernel/tracing/tracing_on').read_text().strip(),rx_hold_cycles=P('/sys/module/lauberhorn/parameters/rx_hold_cycles').read_text().strip())
assert r['kernel_tracing']=='0'
(root/'provenance.json').write_text(json.dumps(r,indent=2)+'\n')
(root/'tad-after.txt').write_text(P('/sys/module/lauberhorn/parameters/l2c_tad_snapshot').read_text())
(root/'kernel-after.txt').write_text(subprocess.check_output(['dmesg'],text=True))
b=io.BytesIO()
with tarfile.open(fileobj=b,mode='w:gz') as t:t.add(root,arcname='dut')
urllib.request.urlopen(urllib.request.Request('http://192.168.255.254:19863/uninstrumented-dut.tar.gz',data=b.getvalue(),method='PUT')).read()
