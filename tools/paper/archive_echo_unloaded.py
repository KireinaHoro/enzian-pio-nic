#!/usr/bin/env python3
"""Freeze full unloaded cohorts from campaigns without requiring loaded completion."""
import argparse
import json
import tempfile
from pathlib import Path
from campaign.experiment import pack
from campaign.finalize import Archive
from campaign.hardware import atomic
from campaign.import_loaded import digest
from campaign.import_unloaded import validate_matched
from campaign.policy import observed_systems


def archive_unloaded(inputs, destination):
    with tempfile.TemporaryDirectory(prefix="echo-unloaded-") as directory:
        root = Path(directory)
        _archive_unloaded(inputs, root)
        pack(root, destination)


def _archive_unloaded(inputs, destination):
    records=[]
    sources=[]
    for source in inputs:
        source=Path(source).resolve()
        cohorts=json.loads(source.read_text())
        for cohort in cohorts:
            cohort['files']={str((source.parent/Path(p)).resolve()):h for p,h in cohort['files'].items()}
        records.extend(cohorts)
        sources.append(dict(path=str(source),sha256=digest(source)))
    validate_matched(records,systems=observed_systems(records))
    for cohort in records:
        if cohort['count']!=10000 or cohort['warmup']!=128*len(cohort.get('chunks',[None])):
            raise ValueError('Only full unloaded cohorts may be archived')
    archive=Archive(destination)
    atomic(destination/'unloaded.json',archive.records(records))
    provenance={}
    for source in inputs:
        parent=Path(source).resolve().parent
        for name in ('config.json','code.json','inheritance-review.json','inherited-unloaded.json'):
            path=parent/name
            if path.exists():
                archived,sha=archive.file(path,digest(path));provenance[archived]=sha
    atomic(destination/'manifest.json',dict(schema=1,status='unloaded-complete',
        systems=list(observed_systems(records)),paper_inputs=True,loaded_complete=False,
        sources=sources,unloaded='unloaded.json',unloaded_sha256=digest(destination/'unloaded.json'),
        provenance=provenance,boundary='Tofino request-TX to reply-RX MAC interval; nominal 1 ns ticks, no absolute calibration',
        instrumentation='CDF probes compiled out; breakdown uses separate probe-enabled cohorts',
        warmup='128 excluded requests per resident-worker capture chunk',
        resources='One RPC worker per system; CS-A64 additionally uses IOKernel control CPU42 and dataplane CPU43',
        limitations='Historical LH/DPDK and new CS-A64 acquisitions retain separate identities. CPU-wire residual is combined, not directional. Loaded data excluded.'))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,nargs='+',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    archive_unloaded(args.input,args.output)
