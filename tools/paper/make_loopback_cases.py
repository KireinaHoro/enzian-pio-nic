#!/usr/bin/env python3
"""Build a 519555 loopback boot case using its matched runtime and an app-only update."""
import argparse
import json
from pathlib import Path
import shlex

ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--size',type=int,choices=(8,64,128,512,1432),required=True)
p.add_argument('--workers',type=int,choices=(1,2,3,4),required=True)
p.add_argument('--label',required=True)
p.add_argument('--app-sha256',required=True)
p.add_argument('--transfer-url',required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
if not a.label.replace('-','').replace('_','').isalnum() or len(a.app_sha256)!=64 or any(c not in '0123456789abcdef' for c in a.app_sha256):p.error('invalid label/hash')
case=json.loads((ROOT/'data/paper/nullrpc/lh-hot-uninstrumented/cases.json').read_text())[0]
case['name']=a.label
case['program']=[x.replace('program-hot-uninstrumented.log','program-'+a.label+'.log') for x in case['program']]
base='/dev/shm/'+a.label
url=a.transfer_url.rstrip('/')
case['cpu']=f'''test "$(uname -r)" = 6.8.0-146-generic
ip link set cpu40g1 down
cpu configure 0c:53:31:03:01:c8 192.168.129.200/18
dmesg -n 4
ip neigh replace 192.168.128.225 lladdr 0c:53:31:03:00:e1 nud permanent dev lauberhorn0
mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
printf '0\\n' > /sys/kernel/tracing/tracing_on
unset LAUBERHORN_SERVER_TRACE_CSV ADDER_SERVER_TRACE_CSV LAUBERHORN_EVENT_TRACE_CSV LAUBERHORN_PMU
mkdir {base}
systemctl start rpcbind
python3 - <<'PYAPP'
import urllib.request,hashlib
from pathlib import Path
p=Path('{base}/microbenchmarks')
p.write_bytes(urllib.request.urlopen({url+'/microbenchmarks-loopback'!r},timeout=60).read())
assert hashlib.sha256(p.read_bytes()).hexdigest()=={a.app_sha256!r}
p.chmod(0o755)
PYAPP
nohup {base}/microbenchmarks loopback{a.size} {a.workers} > {base}/server.log 2>&1 < /dev/null &
echo $! > {base}/server.pid
sleep 2
kill -0 "$(cat {base}/server.pid)"
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > {base}/tad-before.txt
dmesg > {base}/kernel-before.txt
'''
case['client_machine']='zuestoll07'
code=f"import urllib.request,subprocess;code=urllib.request.urlopen({url+'/acquire_loopback_lh.sh'!r}).read().decode();subprocess.run(['unshare','-m','bash','-c',code,'acquire',{a.label+'-setup'!r},{str(a.size)!r},'100','16','1','0',{url!r}],check=True)"
case['client']=shlex.join(['python3','-c',code])
with a.output.open('x') as f:json.dump([case],f,indent=2);f.write('\n')
