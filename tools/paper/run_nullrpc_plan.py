#!/usr/bin/env python3
"""Run a bounded sequential peer plan via the repository console harness.

Acquisition script and plan must already be staged at the transfer URL. This
uses existing reserved machines; it does not provision or reset them. Stop on
the first failure, leaving board state for the documented capture procedure.
"""
import argparse,json,subprocess,sys
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--plan-url',required=True)
p.add_argument('--acquire-url',required=True)
p.add_argument('--transfer-url',required=True)
p.add_argument('--logs',type=Path,required=True)
p.add_argument('--peer-base',default='/dev/shm/paper-nullrpc-20261013')
a=p.parse_args()
# JSON quoting becomes Python string syntax, then the console harness performs
# shell quoting and checksum-validated transfer; no shell interpolation here.
body='''import json,os,subprocess,urllib.request
from pathlib import Path
os.environ['LH_PAPER_PEER_BASE']=PEERBASE
script=Path(PEERBASE)/'acquire-plan.sh'
script.write_bytes(urllib.request.urlopen(ACQUIRE).read())
plan=json.load(urllib.request.urlopen(PLAN))
for run in plan:
 subprocess.run(['bash',str(script),run['label'],str(run['count']),str(run['warmup']),str(run['window']),str(run['gap_ns']),run['mode'],TRANSFER],check=True)
'''.replace('ACQUIRE',repr(a.acquire_url)).replace('PLAN',repr(a.plan_url)).replace('TRANSFER',repr(a.transfer_url)).replace('PEERBASE',repr(a.peer_base))
root=Path(__file__).resolve().parents[2]
subprocess.run([sys.executable,str(root/'tools/enzian/run.py'),'--machine','zuestoll07','--logs',str(a.logs),'--timeout','900','--','/nix/store/lauberhorn-client/bin/python3','-c',body],check=True)
