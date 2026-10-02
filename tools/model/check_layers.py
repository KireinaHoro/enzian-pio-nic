#!/usr/bin/env python3
"""Bounded low-level guarantees and optional endpoint expansion; not a general refinement proof."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from coherence_tables import generate

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT/'model/config/worker/suite.json'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rom',type=Path,required=True)
    p.add_argument('--thx',type=Path,default=ROOT/'deps/thx-fsms')
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--case',action='append')
    p.add_argument('--timeout',type=int,default=300)
    p.add_argument('--workers',type=int,default=1)
    a=p.parse_args()
    jar=os.environ.get('TLA2TOOLS_JAR')
    if not jar:p.error('run inside nix develop')
    catalog=json.loads(CATALOG.read_text())
    selected=a.case or catalog['default']
    if set(selected)-catalog['cases'].keys():p.error('unknown case')
    a.output.mkdir(parents=True,exist_ok=False)
    generated=a.output/'tables'
    tables=generate(a.rom,a.thx,generated)
    scheduling_catalog=ROOT/'model/config/scheduling/suite.json'
    scheduling=json.loads(scheduling_catalog.read_text())
    sources=sorted((ROOT/'model/tla').glob('*/*.tla'))
    snapshot={f:f.read_bytes() for f in sources}
    sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
    summary={'scope':__doc__,'tables':tables,'jar_sha256':sha(Path(jar)),
             'sources':{str(f.relative_to(ROOT)):sha(f) for f in sources+[CATALOG,scheduling_catalog,Path(__file__)]},'cases':{}}
    for name in selected:
        case=catalog['cases'][name];module=case['module']
        base=catalog['line' if module=='LineEndpoint' else 'single' if module=='SingleWorker' else 'expanded_scheduling' if module=='ExpandedScheduling' else 'expanded']
        constants=(scheduling['defaults'][base['defaults_from']] if 'defaults_from' in base else base.get('constants',{}))|case['options']
        invs=base['invariants'];props=base['properties'];violation=case.get('violation')
        if violation:
            props=[violation] if violation in {'Completes','Progress'} else []
            invs=[violation] if not props else base['invariants']
        cfg='SPECIFICATION Spec\nCONSTANTS\nDcTable <- SelectedDcTable\nThxTable <- SelectedThxTable\n'
        cfg+=''.join(f'{k} = {json.dumps(v) if isinstance(v,str) else str(v).upper()}\n' for k,v in constants.items())
        if invs:cfg+='INVARIANTS '+' '.join(invs)+'\n'
        if props:cfg+='PROPERTIES '+' '.join(props)+'\n'
        cfg+='CHECK_DEADLOCK FALSE\n'
        work=a.output/name;work.mkdir()
        for f in sources:(work/f.name).write_bytes(snapshot[f])
        shutil.copyfile(generated/'CoherenceTables.tla',work/'CoherenceTables.tla')
        (work/'Check.tla').write_text(f'---------------- MODULE Check ----------------\nEXTENDS {module}, CoherenceTables\n===============================================\n')
        (work/'Check.cfg').write_text(cfg)
        command=['java','-Xmx2048m','-XX:+UseParallelGC','-cp',str(Path(jar).resolve()),'tlc2.TLC',
                 '-workers',str(a.workers),'-lncheck','final','-config','Check.cfg','Check.tla']
        print(f'{name}: checking',flush=True);start=time.monotonic();log=work/'TLC.log'
        with log.open('w') as out:
            try:code=subprocess.run(command,cwd=work,stdout=out,stderr=subprocess.STDOUT,timeout=a.timeout).returncode
            except subprocess.TimeoutExpired:code=124
        text=log.read_text()
        expected=bool(violation) and ((code==12 and f'Invariant {violation} is violated' in text)
            or (code==13 and props==[violation] and 'Temporal properties were violated' in text))
        passed=expected if violation else code==0 and 'No error has been found' in text
        summary['cases'][name]={'passed':passed,'expected_violation':violation,'returncode':code,
            'config_sha256':sha(work/'Check.cfg'),'log_sha256':sha(log),
            'elapsed_seconds':round(time.monotonic()-start,3),
            'statistics':[line for line in text.splitlines() if 'distinct states found' in line]}
        (a.output/'summary.json').write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n')
        print(f'{name}: {"EXPECTED " + violation if expected else "PASS" if passed else "FAIL"}',flush=True)
        if not passed:print('\n'.join(text.splitlines()[-35:]),flush=True)
    return 0 if all(x['passed'] for x in summary['cases'].values()) else 1

if __name__=='__main__':raise SystemExit(main())
