#!/usr/bin/env python3
"""Losslessly compress a validated full capture and retire only verified duplicates."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--trace',type=Path,required=True)
p.add_argument('--summary',type=Path,required=True)
p.add_argument('--peer-copy',help='exact owned peer RAM trace path to remove after verification')
p.add_argument('--archive-host',help='optional SSH host for verified compressed capture storage')
p.add_argument('--archive-directory',type=Path,help='absolute remote directory; requires --archive-host')
p.add_argument('--logs',type=Path,required=True)
a=p.parse_args();a.logs.mkdir(parents=True,exist_ok=False)
if bool(a.archive_host)!=bool(a.archive_directory):p.error('archive host and directory are required together')
if a.archive_directory and not a.archive_directory.is_absolute():p.error('remote archive directory must be absolute')
summary=json.loads(a.summary.read_text());target=a.trace.with_suffix(a.trace.suffix+'.zst')
if target.exists():raise ValueError('Archive already exists')
if a.trace.stat().st_size!=summary['bytes']:raise ValueError('Capture size mismatch')
subprocess.run(['zstd','-T4','-3','--no-progress',str(a.trace),'-o',str(target)],check=True)
process=subprocess.Popen(['zstd','-dc',str(target)],stdout=subprocess.PIPE)
h=hashlib.sha256();size=0
while data:=process.stdout.read(1<<20):h.update(data);size+=len(data)
process.stdout.close()
if process.wait() or h.hexdigest()!=summary['sha256'] or size!=summary['bytes']:
    raise ValueError('Compressed capture does not reproduce validated bytes')
h=hashlib.sha256()
with target.open('rb') as f:
    while data:=f.read(1<<20):h.update(data)
summary['archive']=dict(file=str(target),sha256=h.hexdigest(),codec='zstd',bytes=target.stat().st_size,verified_raw_sha256=summary['sha256'])
a.summary.write_text(json.dumps(summary,indent=2)+'\n')
a.trace.unlink()
if a.peer_copy:
    if not re.fullmatch(r'/dev/shm/trace-recorder-[0-9]+-[A-Za-z0-9_-]+/trace-dram\.bin',a.peer_copy):
        raise ValueError('Peer path must name the exact owned capture file')
    code=f'''from pathlib import Path
import hashlib
p=Path({a.peer_copy!r});h=hashlib.sha256()
with p.open('rb') as f:
    while data:=f.read(1048576):h.update(data)
assert h.hexdigest()=={summary['sha256']!r}
p.unlink()
print('VERIFIED_DUPLICATE_REMOVED')
'''
    subprocess.run([sys.executable,str(Path(__file__).resolve().parents[1]/'enzian/run.py'),'--machine','zuestoll07','--logs',str(a.logs/'peer'),'--timeout','180','--','python3','-c',code],check=True)
if a.archive_host:
    remote=a.archive_directory/(a.trace.parent.parent.name+'-'+target.name)
    subprocess.run(['ssh',a.archive_host,'mkdir -p -- '+shlex.quote(str(a.archive_directory))],check=True)
    subprocess.run(['rsync','--ignore-existing',str(target),a.archive_host+':'+shlex.quote(str(remote))],check=True)
    verified=subprocess.check_output(['ssh',a.archive_host,'sha256sum -- '+shlex.quote(str(remote))],text=True).split()[0]
    if verified!=summary['archive']['sha256']:raise ValueError('Remote archive hash mismatch')
    summary['archive']['remote']=a.archive_host+':'+str(remote)
    summary['archive']['local_removed']=True
    a.summary.write_text(json.dumps(summary,indent=2)+'\n')
    target.unlink()
print(json.dumps(summary['archive']))
