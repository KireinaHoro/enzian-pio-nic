#!/usr/bin/env python3
"""Fetch just the named result archives; validate paths before extracting."""
import argparse,json,subprocess,tarfile
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('plan',type=Path)
p.add_argument('--destination',type=Path,required=True)
p.add_argument('--gateway',default='enzian-gateway')
p.add_argument('--remote-root',default='/tmp/paper-nullrpc-20261013')
a=p.parse_args();a.destination.mkdir(parents=True,exist_ok=True)
for entry in json.loads(a.plan.read_text()):
 label=entry['label']
 if not label.replace('-','').replace('_','').isalnum():raise ValueError('Unsafe label')
 archive=a.destination/(label+'.tar.gz')
 if not archive.exists():
  subprocess.run(['scp',a.gateway+':'+a.remote_root+'/'+archive.name,str(archive)],check=True)
 with tarfile.open(archive) as f:
  for member in f.getmembers():
   path=Path(member.name)
   if path.is_absolute() or '..' in path.parts or path.parts[0]!=label or not (member.isfile() or member.isdir()):
    raise ValueError('Unexpected archive member '+member.name)
  f.extractall(a.destination)
 summary=json.loads((a.destination/label/'runner/summary.json').read_text())
 print(json.dumps({'run':label,'status':summary['status'],'restored':summary['restored']}),flush=True)
 if summary['status']!='pass' or not summary['restored']:raise SystemExit('Failed run; preserve board state')
