#!/usr/bin/env python3
"""Run one matched LH loopback boot, bounded cohorts, preservation and import.

Stops on any failure. CPU/test failures after a successful boot trigger capture
before returning; no subsequent reset occurs in this command.
"""
import argparse
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile

ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--trace-config',type=Path,required=True)
p.add_argument('--size',type=int,choices=(8,64,128,512,1432),required=True)
p.add_argument('--workers',type=int,choices=(1,2,3,4),required=True)
p.add_argument('--label',required=True)
p.add_argument('--root',type=Path,required=True)
p.add_argument('--kind',choices=('all','latency','workers'),default='all')
p.add_argument('--transfer-root',default='/tmp/loopback-campaign-20261014')
p.add_argument('--transfer-url',default='http://192.168.255.254:18846')
p.add_argument('--app-sha256',default='7dfa8c13e5f79cfb1c0e8d63b2c80e6037cc71ba21624a704e7858dabe78c8b2')
a=p.parse_args()
if not a.label.replace('-','').replace('_','').isalnum():p.error('unsafe label')
a.root.mkdir(parents=True,exist_ok=False)
label=a.label;url=a.transfer_url.rstrip('/');remote='/tmp/lauberhorn-519555'

def run(argv, *, log=None, timeout=None, check=True):
    print(json.dumps(dict(stage=Path(str(argv[0])).name,step=str(argv[1]) if len(argv)>1 else '',label=label)),flush=True)
    if log:
        with Path(log).open('w') as stream:
            return subprocess.run(list(map(str,argv)),stdout=stream,stderr=subprocess.STDOUT,timeout=timeout,check=check)
    return subprocess.run(list(map(str,argv)),timeout=timeout,check=check)

def capture():
    run([sys.executable,ROOT/'tools/enzian/trace_capture.py','capture','--config',a.trace_config,
         '--output',a.root/(label+'-dump')],timeout=7200)

cases=a.root/'cases.json'
run([sys.executable,ROOT/'tools/paper/make_loopback_cases.py','--size',a.size,'--workers',a.workers,'--label',label,'--app-sha256',a.app_sha256,'--transfer-url',url,'--output',cases])
full=a.root/'full-plan.json'
run([sys.executable,ROOT/'tools/paper/make_loopback_plan.py','--system','LH','--generator-cores','8','--prefix',label,'--output',full])
selected=[r for r in json.loads(full.read_text()) if r['payload_bytes']==a.size and r['workers']==a.workers and (a.kind=='all' or r['kind']==a.kind)]
if not selected:raise ValueError('No cohorts match requested case')
plan=dict(campaign=label,setup_count=116,runs=selected)
plan_path=a.root/'plan.json';plan_path.write_text(json.dumps(plan,indent=2)+'\n')
peer_plan=a.root/'peer-plan.json';peer_plan.write_text(json.dumps(selected,indent=2)+'\n')
run(['rsync',peer_plan,'enzian-gateway:'+a.transfer_root+'/'+label+'-plan.json'])
run([sys.executable,ROOT/'tools/enzian/run.py','--machine','zuestoll14','--logs',a.root/'image-preflight','--timeout','120','--','bash','-c',"printf '%s  %s\\n' 85b286ea9e82c5cda79ddc01df4df79c4d40e3099a1574e24377c3b944817961 /var/tmp/lauberhorn-kernel146/deploy.img | sha256sum --check --status"])
result=run([sys.executable,ROOT/'tools/enzian/test.py',cases,'--logs',a.root/'board','--repeats','1'],check=False)
board=json.loads((a.root/'board/summary.json').read_text())
if result.returncode:
    if board and board[-1].get('boot_exit')==0:
        capture()
    raise SystemExit('Board preflight failed; state retained, no further reset')
body=f'''set -euo pipefail
python3 - <<'PYRUN'
import json,subprocess,urllib.request
url={url!r}
script=urllib.request.urlopen(url+'/acquire_loopback_lh.sh').read().decode()
plan=json.load(urllib.request.urlopen(url+'/{label}-plan.json'))
for r in plan:
    image='/dev/shm/loopback-stage-20261014b/loopback-v5.img'
    subprocess.run(['unshare','-m','bash','-c',script,'acquire',r['label'],str(r['payload_bytes']),str(r['count']),str(r['warmup']),str(r['window']),str(r['gap_ns']),url,','.join(map(str,r['generator_lcores'])),image],check=True)
PYRUN
'''
script=a.root/'acquire.sh';script.write_text(body)
result=run([sys.executable,ROOT/'tools/enzian/run.py','--machine','zuestoll07','--logs',a.root/'acquisition','--timeout','1800','--script',script],check=False)
if result.returncode:
    capture();raise SystemExit('Acquisition failed; full trace preserved, no further reset')
export="import urllib.request,sys;sys.argv="+repr(['export',label,str(a.size),str(a.workers),a.app_sha256,url])+";exec(urllib.request.urlopen("+repr(url+'/export_loopback_lh.py')+").read())"
result=run([sys.executable,ROOT/'tools/enzian/run.py','--machine','zuestoll14','--logs',a.root/'export','--timeout','120','--','python3','-c',export],check=False)
capture()
if result.returncode:raise SystemExit('Provenance export failed; full trace preserved, no further reset')
trace=a.root/(label+'-dump/trace/trace-dram.bin')
map_path=ROOT/'data/paper/nullrpc/uninstrumented-trace-map.json'
check=a.root/'trace-check.json'
expected=116+sum(r['count']+r['warmup'] for r in selected)
run(['nix','develop','-c','python3',ROOT/'tools/paper/check_nullrpc_capture.py',trace,'--map',map_path,'--expected-rpcs',expected,'--output',check])

archives=a.root/'archives';archives.mkdir()
for name in [r['label']+'.tar.gz' for r in selected]+[label+'-setup.tar.gz',label+'-dut.tar.gz']:
    run(['rsync','enzian-gateway:'+a.transfer_root+'/'+name,archives/name])
with tarfile.open(archives/(label+'-dut.tar.gz')) as archive:
    for member in archive:
        path=Path(member.name)
        if path.is_absolute() or '..' in path.parts or path.parts[0]!='dut' or not (member.isfile() or member.isdir()):raise ValueError('Unsafe DUT archive')
    archive.extractall(a.root)
dut=a.root/'dut';health=a.root/'health.json'
run([sys.executable,ROOT/'tools/enzian/assess_tad.py','--before',dut/'tad-before.txt','--after',dut/'tad-after.txt','--kernel-before',dut/'kernel-before.txt','--kernel-after',dut/'kernel-after.txt','--output',health])
destination=ROOT/'data/paper/loopback'/label
run(['nix','develop','-c','python3',ROOT/'tools/paper/import_loopback_lh.py','--plan',plan_path,'--archives',archives,'--trace',trace,'--map',map_path,'--capture-check',check,'--health',health,'--output',destination,'--manifest',ROOT/'data/paper/loopback/manifest.json'])
for source,name in [(a.root/'board/summary.json','board-summary.json'),(cases,'cases.json'),(dut/'provenance.json','dut-provenance.json'),(a.root/(label+'-dump/integrity.json'),'jtag-check.json'),(a.root/(label+'-dump/summary.json'),'capture-summary.json')]:
    shutil.copyfile(source,destination/name)
shutil.copyfile(archives/(label+'-setup.tar.gz'),destination/'setup-acquisition.tar.gz')
import gzip
for name in ('kernel-before.txt','kernel-after.txt','tad-before.txt','tad-after.txt'):
    (destination/(name+'.gz')).write_bytes(gzip.compress((dut/name).read_bytes(),mtime=0))
run([sys.executable,ROOT/'tools/paper/archive_trace.py','--trace',trace,'--summary',a.root/(label+'-dump/summary.json'),'--peer-copy','/dev/shm/trace-recorder-519555-'+label+'/trace-dram.bin','--logs',a.root/'archive','--archive-host','enzian-gateway','--archive-directory',a.transfer_root+'/full-captures'])
shutil.copyfile(a.root/(label+'-dump/summary.json'),destination/'capture-summary.json')
archived=json.loads((destination/'capture-summary.json').read_text())['archive']
summary=dict(passed=True,label=label,size=a.size,workers=a.workers,cohorts=len(selected),measured=sum(r['count'] for r in selected),trace=archived.get('remote',archived['file']))
(a.root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary),flush=True)
