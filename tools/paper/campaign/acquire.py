"""Bounded remote role execution through the Enzian console harness.

The caller owns reservations, artifact checks, boot/state preservation and trace
controls. This module only stages exact role requests, runs them, and preserves
their archives. Loaded acquisition has no trace-readout path.
"""
import json
import csv
from pathlib import Path
import subprocess
import sys
import tarfile

from .import_loaded import digest

ROOT=Path(__file__).resolve().parents[3]
PEER=Path(__file__).with_name('peer.py')


def cpu_set(text):
    result=set()
    for part in text.strip().split(','):
        if not part: continue
        bounds=part.split('-')
        if len(bounds)==1: result.add(int(part))
        elif len(bounds)==2:
            first,last=map(int,bounds)
            if last<first: raise ValueError('Reversed CPU range')
            result.update(range(first,last+1))
        else: raise ValueError('Invalid CPU range')
    return result


def validate_role(root, request, binary_sha):
    root=Path(root)
    read=lambda name:json.loads((root/name).read_text())
    for name,sha in read('files.json').items():
        path=Path(name)
        if path.is_absolute() or '..' in path.parts or digest(root/path)!=sha:
            raise ValueError('Uploaded role evidence hash mismatch')
    if read('request.json')!=request: raise ValueError('Role request provenance differs')
    outcome=read('result.json');wrapper=read('runner/summary.json');before=read('runner/before.json')
    if outcome['returncode']!=0 or outcome['error'] or wrapper['status']!='pass' or wrapper['restored'] is not True:
        raise ValueError('Role execution or NIC restoration failed')
    if before['binary_sha256']!=binary_sha or before['lcores']!=request['cpus']:
        raise ValueError('Role binary or CPU placement differs')
    if not set(request['cpus'])<=cpu_set(before['isolated_cpus']):
        raise ValueError('Role used nonisolated CPUs')
    if request['role']=='server':
        capability=read('manifest.json')['responderTracing'][Path(request['binary']).name]
        if capability!=(request['instrumentation']=='breakdown'):
            raise ValueError('Responder compiled instrumentation differs')
    timestamped=request['role']=='server' and request['mode']=='unloaded'
    provenance=before['timestamp_provenance']
    if timestamped:
        if not provenance or not provenance['clocks'] or set(provenance['modules'])!={'nicvf','thunder_bgx','cavium_ptp'}:
            raise ValueError('Incomplete hardware timestamp provenance')
    elif provenance is not None:
        raise ValueError('Unexpected hardware timestamp instrumentation')
    result=dict(files={str(p.resolve()):digest(p) for p in root.rglob('*') if p.is_file()})
    if request['role']=='server' and request['mode']=='loaded':
        with (root/'samples.csv').open() as stream: workers=list(csv.DictReader(stream))
        if len(workers)!=len(request['cpus']) or [int(r['lcore']) for r in workers]!=request['cpus']:
            raise ValueError('Responder worker placement differs')
        activity=[int(r['measured']) for r in workers]
        if any(n<=0 for n in activity): raise ValueError('Inactive responder worker')
        nic=read('samples.csv.nic.json')
        if any(nic[k] for k in ('rx_errors','tx_errors','rx_no_buffer')):
            raise ValueError('Responder NIC health/resource failure')
        result.update(worker_activity=activity,nic=nic)
    return result


def extract(archive, destination, expected):
    """Never follow archive links or allow evidence to escape its cohort."""
    destination=Path(destination)
    with tarfile.open(archive) as stream:
        names=set()
        for member in stream.getmembers():
            path=Path(member.name)
            if (path.is_absolute() or '..' in path.parts or not path.parts or
                    path.parts[0]!=expected or member.name in names or
                    not (member.isfile() or member.isdir())):
                raise ValueError('Unsafe or duplicate acquisition archive member')
            names.add(member.name)
        destination.mkdir(exist_ok=False)
        stream.extractall(destination,filter='data')
    return destination/expected


def remote_script(config_url, script_url, script_sha, image, image_sha):
    # Hash the image by streaming; full image reads must not consume host RAM.
    code=f'''import hashlib,subprocess,urllib.request
from pathlib import Path
p=Path({image!r})
with p.open('rb') as stream:
 sha=hashlib.sha256()
 for block in iter(lambda:stream.read(1024*1024),b''): sha.update(block)
 h=sha.hexdigest()
if h!={image_sha!r}: raise ValueError('Staged client image hash mismatch')
script=urllib.request.urlopen({script_url!r},timeout=30).read()
if hashlib.sha256(script).hexdigest()!={script_sha!r}: raise ValueError('Role script hash mismatch')
request=urllib.request.urlopen({config_url!r},timeout=30).read()
import json
config=json.loads(request)
namespace='''+repr('''set -eu
mount --make-rprivate /
if mountpoint -q /nix/store; then umount /nix/store; fi
mount -o loop,ro "$1" /nix/store
export PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin
unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME
exec python3 -c "$2"
''')+'''
payload=script.decode().split("if __name__=='__main__':")[0]+'\\nrun('+repr(config)+')\\n'
subprocess.run(['unshare','-m','bash','-c',namespace,'echo-role',str(p),payload],check=True)
'''
    return "python3 - <<'ECHOROLE'\n"+code+"\nECHOROLE\n"


def roles(directory, configs, *, gateway, transfer_root, image, image_sha):
    """Run server/client concurrently, then collect every available artifact.

    configs maps role to (machine, request). Failures still collect both roles'
    archives and retain console logs. The peer bounds its own cleanup lifetime.
    """
    directory=Path(directory);directory.mkdir(exist_ok=False)
    processes=[];logs=[];evidence={};errors=[]
    script_name=directory.name+'-peer.py'
    subprocess.run(['rsync',str(PEER),gateway+':'+transfer_root+'/'+script_name],check=True,timeout=60)
    try:
        for role,(machine,request) in configs.items():
            name=request['label']+'-'+role
            request_path=directory/(name+'.json')
            request_path.write_text(json.dumps(request,indent=2)+'\n')
            subprocess.run(['rsync',str(request_path),gateway+':'+transfer_root+'/'+request_path.name],check=True,timeout=60)
            url=request['transfer_url'].rstrip('/')
            script=directory/(role+'.sh')
            script.write_text(remote_script(url+'/'+request_path.name,url+'/'+script_name,digest(PEER),image,image_sha))
            log=(directory/(role+'.log')).open('w');logs.append(log)
            timeout=request['timeout_seconds']+900
            process=subprocess.Popen([sys.executable,str(ROOT/'tools/enzian/run.py'),
                '--gateway',gateway,'--machine',machine,'--logs',str(directory/(role+'-console')),
                '--timeout',str(timeout),'--script',str(script)],stdout=log,stderr=subprocess.STDOUT)
            processes.append((role,process,timeout))
        for role,process,timeout in processes:
            try:
                code=process.wait(timeout=timeout+120)
                if code: errors.append(f'{role} harness exit {code}')
            except subprocess.TimeoutExpired:
                errors.append(f'{role} harness deadline exceeded')
    finally:
        for role,process,_ in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired: process.kill();process.wait()
        for log in logs: log.close()
        for role,(_,request) in configs.items():
            name=Path(request['output']).name
            archive=directory/(name+'.tar.gz')
            result=subprocess.run(['rsync',gateway+':'+transfer_root+'/'+archive.name,str(archive)],
                                  capture_output=True,text=True,timeout=600)
            if result.returncode:
                errors.append(f'{role} archive unavailable: {result.stderr[-1000:]}');continue
            evidence[role]=str(extract(archive,directory/(role+'-extracted'),name))
        (directory/'summary.json').write_text(json.dumps(dict(passed=not errors,errors=errors,evidence=evidence),indent=2)+'\n')
    if errors: raise RuntimeError('; '.join(errors)+f'; preserved at {directory}')
    return evidence
