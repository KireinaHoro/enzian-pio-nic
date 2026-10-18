#!/usr/bin/env bash
# Run in a private mount namespace through tools/enzian/run.py on each reserved host.
# ROLE PLAN_URL TRANSFER_URL IMAGE BASE; matching client/server plans are required.
set -euo pipefail
role=$1 plan=$2 transfer=$3 image=$4 base=$5
[[ $role == server || $role == client ]]
mkdir "$base"
# The caller creates a private namespace; discard any inherited image mount.
mount --make-rprivate /
if mountpoint -q /nix/store; then umount /nix/store; fi
mount -o loop,ro "$image" /nix/store
export PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin
unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME LH_ECHO_PROFILE LH_ECHO_DELAY_NS
python3 - "$role" "$plan" "$transfer" "$base" "$image" <<'PY'
import hashlib,io,json,os,subprocess,sys,tarfile,time,urllib.error,urllib.request
from pathlib import Path
role,plan_url,url,base,image=sys.argv[1:]; base=Path(base)
plan=json.load(urllib.request.urlopen(plan_url,timeout=30))
(base/'plan.json').write_text(json.dumps(plan,indent=2)+'\n')
(base/'image.sha256').write_text(hashlib.sha256(Path(image).read_bytes()).hexdigest()+'\n')
manifest=Path('/nix/store/lauberhorn-client/manifest.json').read_bytes()

def put(name,data):
    request=urllib.request.Request(url+'/'+name,data=data,method='PUT')
    urllib.request.urlopen(request,timeout=120).read()

for run in plan:
    label=run['label']; out=base/(label+'-'+role); out.mkdir()
    (out/'manifest.json').write_bytes(manifest)
    (out/'run.json').write_text(json.dumps(run,indent=2)+'\n')
    (out/'image.sha256').write_bytes((base/'image.sha256').read_bytes())
    env=dict(os.environ)
    env.pop('LH_ECHO_PROFILE',None); env.pop('LH_ECHO_SOFTWARE_CONTROL',None)
    env.pop('LH_ECHO_BATCHED',None)
    env.update(LH_BENCH_WINDOW=str(run['window']),LH_BENCH_GAP_NS=str(run['gap_ns']),LH_BENCH_FLOWS=str(run['flows']))
    runner=['lh-dpdk-run','--logs',str(out/'runner'),'--lcore',('45' if run['workers']==1 else '44') if role=='server' else '6','--no-iommu']
    if role=='server':
        ready=out/'ready'; env['LH_ECHO_READY_FILE']=str(ready)
        if run['workers']>1: runner+=['--worker-lcores',','.join(str(44+i) for i in range(1,run['workers']))]
        if run['ptp']: runner+=['--ptp-bgx','4','--manifest','/nix/store/lauberhorn-client/manifest.json']
        else:
            env['LH_ECHO_SOFTWARE_CONTROL']='1'
            env['LH_ECHO_BATCHED']='1'
        endpoint=['192.168.129.193','192.168.128.225','0c:53:31:03:00:e1','49152']
        binary='echo_dpdk'
    else:
        generators=run.get('generator_lcores',[6])
        if not generators or generators[0]!=6 or len(set(generators))!=len(generators):
            raise ValueError('Invalid generator CPU list')
        if len(generators)>1: runner+=['--worker-lcores',','.join(map(str,generators[1:]))]
        deadline=time.monotonic()+180
        while True:
            try:
                urllib.request.urlopen(url+'/'+label+'-ready',timeout=10).read(); break
            except urllib.error.HTTPError as e:
                if e.code!=404 or time.monotonic()>deadline: raise
                time.sleep(.2)
        endpoint=['192.168.128.225','192.168.129.193','0c:53:31:03:01:c1','12345']
        binary='bench_dpdk'
    command=runner+['/nix/store/lauberhorn-client/bin/'+binary,'--',*endpoint,str(run['count']),str(run['warmup']),'2000000','loopback'+str(run['payload_bytes']),str(out/'samples.csv')]
    failure=None
    with (out/'runner.log').open('w') as log:
        process=subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            if role=='server':
                deadline=time.monotonic()+90
                while not ready.exists() and process.poll() is None and time.monotonic()<deadline: time.sleep(.1)
                if not ready.exists(): raise RuntimeError('server readiness failed')
                put(label+'-ready',b'ready\n')
            rc=process.wait(timeout=180)
        except Exception as error:
            failure=str(error)
        finally:
            if process.poll() is None:
                process.terminate(); process.wait(timeout=30)
            rc=process.returncode
    if failure: (out/'error.txt').write_text(failure+'\n')
    (out/'exit-code').write_text(str(rc)+'\n')
    data=io.BytesIO()
    with tarfile.open(fileobj=data,mode='w:gz') as archive: archive.add(out,arcname=out.name)
    put(out.name+'.tar.gz',data.getvalue())
    if rc or failure: raise RuntimeError(f'{label} {role} failed: {rc}; evidence uploaded')
    print(label,role,'passed',flush=True)
PY
