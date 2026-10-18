#!/usr/bin/env python3
"""Run a paired loopback matrix on the reserved DUT/peer and preserve its archives."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--plan',type=Path,required=True)
p.add_argument('--root',type=Path,required=True)
p.add_argument('--label',required=True)
p.add_argument('--image',default='/dev/shm/loopback-stage-20261014b/loopback-v5.img')
p.add_argument('--transfer-root',default='/tmp/loopback-campaign-20261014')
p.add_argument('--transfer-url',default='http://192.168.255.254:18846')
a=p.parse_args()
if not a.label.replace('-','').replace('_','').isalnum():p.error('unsafe label')
a.root.mkdir(parents=True,exist_ok=False)
plan=json.loads(a.plan.read_text());url=a.transfer_url.rstrip('/')
subprocess.run(['rsync',str(a.plan),'enzian-gateway:'+a.transfer_root+'/'+a.label+'-plan.json'],check=True)
subprocess.run(['rsync',str(ROOT/'tools/paper/acquire_loopback_dpdk.sh'),'enzian-gateway:'+a.transfer_root+'/'+a.label+'-acquire.sh'],check=True)
processes=[];logs=[]
for role,machine in [('server','zuestoll14'),('client','zuestoll07')]:
    code=f'''import subprocess,urllib.request,json
from pathlib import Path
role={role!r}
script=urllib.request.urlopen({url+'/'+a.label+'-acquire.sh'!r}).read().decode()
base={('/dev/shm/'+a.label+'-'+role)!r}
original=None
try:
 if role=='server':
  original=json.loads(subprocess.check_output(['ip','-j','addr','show','dev','cpu40g1']))[0]
  assert not original['addr_info'] and 'UP' not in original['flags'], 'DUT data NIC must start down without addresses'
  subprocess.run(['ip','addr','add','192.168.129.193/18','dev','cpu40g1','noprefixroute'],check=True)
  subprocess.run(['ip','link','set','cpu40g1','up'],check=True)
 subprocess.run(['unshare','-m','bash','-c',script,'acquire',role,{url+'/'+a.label+'-plan.json'!r},{url!r},{a.image!r},base],check=True)
finally:
 if original is not None and not original['addr_info'] and 'UP' not in original['flags']:
  subprocess.run(['ip','addr','del','192.168.129.193/18','dev','cpu40g1'],check=True)
  subprocess.run(['ip','link','set','cpu40g1','down'],check=True)
'''
    script=a.root/(role+'.sh');script.write_text("python3 - <<'PYREMOTE'\n"+code+"\nPYREMOTE\n")
    log=(a.root/(role+'.log')).open('w');logs.append(log)
    processes.append(subprocess.Popen([sys.executable,str(ROOT/'tools/enzian/run.py'),'--machine',machine,'--logs',str(a.root/role),'--timeout',str(max(600,len(plan)*60)),'--script',str(script)],stdout=log,stderr=subprocess.STDOUT))
status=[p.wait() for p in processes]
for f in logs:f.close()
archives=a.root/'archives';archives.mkdir()
missing=[]
for run in plan:
 for role in ('client','server'):
  name=run['label']+'-'+role+'.tar.gz'
  r=subprocess.run(['rsync','enzian-gateway:'+a.transfer_root+'/'+name,str(archives/name)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  if r.returncode:missing.append(name)
summary=dict(passed=not any(status) and not missing,role_exit_codes=status,missing_archives=missing,cohorts=len(plan))
(a.root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
raise SystemExit(not summary['passed'])
