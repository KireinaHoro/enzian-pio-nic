import subprocess,time,sys,pathlib
label=sys.argv[1]
b=pathlib.Path('out/nullrpc-contiguous')
py='/nix/store/qgsbn1s0z107gjyhisswxpzxgz99w621-python3-3.14.7-env/bin/python3'
remote='/scratch/pengxu/nullrpc-contiguous/'
def ssh(host,*args):
 return subprocess.run(['ssh','-o','BatchMode=yes',host,*args],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=15)
def wait(predicate,seconds=300):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if any(p.poll() not in (None,0) for p in procs):raise RuntimeError('remote stage failed; inspect compact stage output before retry')
  if predicate():return
  time.sleep(1)
 raise RuntimeError('stage readiness timed out')
procs=[]
for machine,stage,script,args in [('zuestoll14','capture',sys.argv[2] if len(sys.argv)>2 else 'capture-phase.sh',[label]),('zuestoll07','client-run',label+'-client.sh',[])]:
 f=(b/(label+'-'+stage+'.log')).open('w')
 procs.append(subprocess.Popen([py,'tools/enzian/run.py','--machine',machine,'--logs',str(b/(label+'-'+stage)),'--timeout','720','--','bash',remote+script,*args],stdout=f,stderr=subprocess.STDOUT))
wait(lambda:ssh('enzian-gateway','test','-f',remote+label+'-client-ready').returncode==0)
print('CLIENT_READY',flush=True)
wait(lambda:ssh('enzian-gateway','test','-f',remote+label+'-ready').returncode==0)
print('KERNEL_READY',flush=True)
r=ssh('enzian-ba2','touch','/tmp/lauberhorn-518689/'+label+'-ready');r.check_returncode()
log=b/(label+'-window.log')
f=log.open('w')
v=subprocess.Popen(['ssh','enzian-ba2','/opt/Xilinx/Vivado/2023.2/bin/vivado -mode batch -nolog -nojournal -source /tmp/lauberhorn-518689/nullrpc-window.tcl -tclargs window '+label],stdout=f,stderr=subprocess.STDOUT)
wait(lambda:any(line.startswith('WINDOW_START trace_dma_error_1') for line in log.read_text().splitlines()))
ssh('enzian-gateway','touch',remote+label+'-go').check_returncode()
print('FPGA_READY_TRAFFIC_STARTED',flush=True)
wait(lambda:ssh('enzian-gateway','grep','-q','^completed=',remote+label+'-client/client.log').returncode==0 or ssh('enzian-gateway','test','-f',remote+label+'-done').returncode==0)
ssh('enzian-ba2','touch','/tmp/lauberhorn-518689/'+label+'-done').check_returncode()
print('TRAFFIC_DONE',flush=True)
wait(lambda:ssh('enzian-gateway','test','-f',remote+label+'-done').returncode==0)
rc=[p.wait(timeout=60) for p in procs]+[v.wait(timeout=60)]
print('STAGE_EXITS',rc,flush=True)
if any(rc):raise RuntimeError('stage failed; preserve logs and trace')
print('WINDOW_CAPTURE_SUCCEEDED',flush=True)
