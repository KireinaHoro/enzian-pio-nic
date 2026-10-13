#!/usr/bin/env python3
"""Import validated client/runtime cohorts into tracked paper data with provenance.

PLAN is JSON with hardware_revision, pipeline, server_csv, server_status,
setup_count and runs (label, directory, condition, workers, scaling).
Runtime rows are ordered by RX-unblock time; each drained cohort must contain
exactly the client's request IDs, with no failure/loss or unattributed rows.
"""
import argparse,csv,gzip,hashlib,json
from pathlib import Path
from plot_nullrpc import DATA, measured


def archive(source, destination):
    data=source.read_bytes()
    with destination.open('wb') as f:
        with gzip.GzipFile(filename='',mode='wb',fileobj=f,mtime=0) as z:z.write(data)
    return hashlib.sha256(destination.read_bytes()).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('plan',type=Path)
    args=p.parse_args();plan=json.loads(args.plan.read_text())
    status=json.loads(Path(plan['server_status']).read_text())
    if not status['loss_free'] or status['dropped']:raise ValueError('Runtime capture loss')
    server=list(csv.DictReader(Path(plan['server_csv']).open()))
    if len(server)!=status['rows'] or any(r['ok']!='1' for r in server):raise ValueError('Invalid runtime rows')
    server.sort(key=lambda r:int(r['server_rx_unblock_ns']))
    cursor=plan['setup_count']; entries=[]; sources=[]; preparations=[]
    for run in plan['runs']:
        root=Path(run['directory']); client=root/'client.csv'
        summary=json.loads((root/'runner/summary.json').read_text())
        meta=json.loads((root/'client.csv.load.json').read_text())
        if summary['status']!='pass' or not summary['restored'] or meta['failed']:
            raise ValueError('Failed/unrestored client run '+run['label'])
        all_client=list(csv.DictReader(client.open()));rows,values=measured(client)
        if len(rows)!=meta['expected_measured'] or len(all_client)!=meta['attempted_including_warmup']:
            raise ValueError('Client count mismatch')
        block=server[cursor:cursor+len(all_client)];cursor+=len(all_client)
        if sorted(int(r['request_id']) for r in block)!=sorted(int(r['request_id']) for r in all_client):
            raise ValueError('Cannot join drained runtime cohort '+run['label'])
        measured_ids={r['request_id'] for r in rows}
        measured_block=[r for r in block if r['request_id'] in measured_ids]
        cpus=sorted(set(int(r['rx_cpu_id']) for r in measured_block))
        tids=sorted(set(int(r['server_tid']) for r in measured_block))
        if len(tids)>run['workers']:raise ValueError('More runtime threads than pool')
        activations={(r['server_tid'],r['activation_index']) for r in measured_block}
        if run['condition']=='cold' and len(activations)!=len(rows):
            raise ValueError('Cold requests do not each have a distinct activation')
        if run['condition']=='cold':
            flush=Path(str(client)+'.flush.csv')
            preparation=list(csv.DictReader(flush.open()))
            if meta['flush_every']!=1 or len(preparation)!=len(all_client)*meta['flush_rounds'] or any(
                    r['ok']!='1' or r['received']!=r['expected'] for r in preparation):
                raise ValueError('Incomplete cold preparation')
            preparations.append((flush,DATA/(run['label']+'-flush.csv.gz')))
        if run['condition']=='hot' and len(activations)!=len(tids):
            raise ValueError('Hot cohort changed activation during measurement')
        if run.get('scaling') and (len(tids)!=run['workers'] or len(cpus)!=len(tids)):
            raise ValueError('Scaling did not exercise the requested resident workers')
        destination=DATA/(run['label']+'.csv.gz')
        if destination.exists():raise ValueError('Refusing to overwrite '+str(destination))
        sources.append((client,destination))
        start,end=meta['observation_start_ns'],meta['observation_end_ns']
        if end<=start or any(not start<=int(r['client_call_enter_ns'])<int(r['client_call_exit_ns'])<=end for r in rows):
            raise ValueError('Invalid measurement interval')
        entries.append(dict(run,file=destination.name,
            measured_count=len(rows),warmup_count=len(all_client)-len(rows),
            observation_start_ns=meta['observation_start_ns'],observation_end_ns=meta['observation_end_ns'],
            window=meta['window'],gap_ns=meta['gap_ns'],observed_workers=len(tids),linux_cpus=cpus,
            server_tids=tids,
            measured_activations=len(activations),
            pipeline=plan['pipeline'],hardware_revision=plan['hardware_revision'],
            runtime_evidence=plan['label']+'-server.csv.gz'))
    if cursor!=len(server):raise ValueError('Unattributed runtime rows')
    for entry,(source,destination) in zip(entries,sources):
        entry['sha256']=archive(source,destination)
    for source,destination in preparations:
        if destination.exists():raise ValueError('Preparation archive already exists')
        archive(source,destination)
    archive(Path(plan['server_csv']),DATA/(plan['label']+'-server.csv.gz'))
    (DATA/(plan['label']+'-provenance.json')).write_text(json.dumps(plan,indent=2)+'\n')
    manifest=json.loads((DATA/'manifest.json').read_text())
    manifest['load_runs'].extend(entries)
    (DATA/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(entries,indent=2))


if __name__=='__main__':main()
