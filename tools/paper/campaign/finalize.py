"""Archive a complete measured campaign and validate its five replacement PDFs."""
import copy
import gzip
import json
import os
from pathlib import Path
import shutil
import sys

from .acquire import ROOT
from .hardware import atomic
from .import_loaded import digest, load
from .recovery import invalidated
from .import_unloaded import validate_matched
from .policy import SYSTEMS, SIZES, WORKERS, candidates, loss


def verify_loaded(records, plans):
    expected={(s,n,w) for s in SYSTEMS for n in SIZES for w in WORKERS}
    keys=[(r['system'],r['size'],r['workers']) for r in records]
    if len(keys)!=len(expected) or set(keys)!=expected: raise ValueError('Incomplete full matrix')
    frozen={(r['system'],r['size'],r['workers']):r['rates'] for r in plans}
    if set(frozen)!=expected: raise ValueError('Incomplete frozen grid')
    for key,record in zip(keys,records):
        rates=[p['rate'] for p in record['points']]
        if rates!=sorted(set(rates)) or rates[:len(frozen[key])]!=frozen[key]:
            raise ValueError('Frozen grid was discarded or reordered')
        if not any(loss(t)>.01 for p in record['points'] for t in p['trials']):
            raise ValueError('Full grid never crossed the loss boundary')
        ranked=candidates(record['points'])
        if [p['rate'] for p in record['confirmations']]!=[p['rate'] for p in ranked[:len(record['confirmations'])]]:
            raise ValueError('Sustained candidate fallback differs from policy')
        if record['confirmed']!=record['confirmations'][-1]: raise ValueError('Wrong confirmed candidate')
        for point in record['points']+record['confirmations']:
            sustained=point in record['confirmations']
            if len(point['trials'])!=1: raise ValueError('Expected one trial per loaded point')
            for trial in point['trials']:
                if (trial.get('schema') not in (2,3) or trial['warmup_ns']!=2_000_000_000
                        or trial.get('benchmark')!='echo'+str(record['size'])):
                    raise ValueError('Missing full-run schedule boundary provenance')
                if trial['duration_ns']!=(60 if sustained else 10)*1_000_000_000:
                    raise ValueError('Wrong measured duration')
                csvs=[p for p in trial['files'] if p.endswith(('.csv','.csv.gz'))]
                summaries=[p for p in trial['files'] if p.endswith('.open.json')]
                if len(csvs)!=1 or len(summaries)!=1: raise ValueError('Ambiguous client evidence')
                actual,_=load(csvs[0],summaries[0],collect_latencies=False)
                if actual!=trial or trial['rate']!=point['rate']: raise ValueError('Trial evidence changed')
        if any(loss(t)>.01 for t in record['confirmed']['trials']): raise ValueError('Sustained confirmation failed')
        if any(all(loss(t)<=.01 for t in p['trials']) for p in record['confirmations'][:-1]):
            raise ValueError('Skipped a successful sustained candidate')


class Archive:
    def __init__(self, root):
        self.root=Path(root);self.raw=self.root/'raw';self.raw.mkdir(parents=True,exist_ok=False)
        self.cache={}

    def file(self, source, expected):
        source=Path(source)
        if str(source) in self.cache: return self.cache[str(source)]
        if digest(source)!=expected: raise ValueError('Source evidence changed: '+str(source))
        target=self.raw/(expected[:20]+'-'+source.name)
        compress=source.suffix in ('.csv','.bin','.log','.txt')
        if compress: target=target.with_name(target.name+'.gz')
        if not target.exists():
            if compress:
                with source.open('rb') as original,target.open('xb') as destination:
                    with gzip.GzipFile(filename='',fileobj=destination,mode='wb',mtime=0) as stream:
                        shutil.copyfileobj(original,stream,1024*1024)
            else:
                try: os.link(source,target)
                except OSError: shutil.copyfile(source,target)
        result=str(target.relative_to(self.root)),digest(target)
        self.cache[str(source)]=result
        return result

    def records(self, value):
        if isinstance(value,list): return [self.records(v) for v in value]
        if not isinstance(value,dict): return value
        result={k:self.records(v) for k,v in value.items() if k!='files'}
        if 'files' in value: result['files']=dict(self.file(p,sha) for p,sha in value['files'].items())
        return result


def finalize(config,h,request,campaign):
    from .adapter import pdf_checks
    if config.get('tofino'):
        backend=config['tofino']
        h.command([sys.executable,Path(backend['repository'])/'tools/qualify-clock.py',
            '--switch-control',backend['control_socket'],'--output',h.directory/'clock-after'],
            'clock-after',timeout=180)
    loaded=json.loads(Path(request['loaded']).read_text());plans=json.loads(Path(request['grid']).read_text())
    unloaded=json.loads(Path(request['unloaded']).read_text())
    verify_loaded(loaded,plans);validate_matched(unloaded)
    if any(c['count']!=10000 or c['warmup']!=128*len(c.get('chunks',[None])) for c in unloaded): raise ValueError('Wrong full unloaded sample count')
    destination=Path(config['archive_directory']).resolve()/h.directory.name
    archive=Archive(destination)
    atomic(destination/'loaded.json',archive.records(loaded))
    atomic(destination/'unloaded.json',archive.records(unloaded))
    atomic(destination/'hardware.json',config)
    atomic(destination/'recovery-history.json',[json.loads(p.read_text()) for p in sorted((campaign/'recoveries').glob('*/review.json'))])
    from .policy import validate_activity, validate_activity_coverage
    activities={}
    provenance={}
    for receipt in (campaign/'stages').glob('*/receipt.json'):
        if invalidated(campaign,receipt): continue
        saved=json.loads(receipt.read_text())
        if any(digest(p)!=sha for p,sha in saved['hashes'].items()): raise ValueError('Stage evidence hash mismatch')
        result=saved['result']
        if 'trial' in result:
            if not result['health'] or not result['restored']:
                raise ValueError('Final worker/health/restoration gate failed')
            request_path=next(p for p in saved['hashes'] if Path(p).name=='request.json' and Path(p).parent.name.startswith('attempt-'))
            cohort=json.loads(Path(request_path).read_text())
            key=cohort['system'],cohort['payload_bytes'],cohort['workers']
            validate_activity(result['worker_activity'],cohort['workers'])
            activities.setdefault(key,[]).append(result['worker_activity'])
            if result['trace_readouts']!=0 or result['software_profiling'] or result['fpga_recording']:
                raise ValueError('Final loaded instrumentation gate failed')
        # Keep complete acquisition archives, health, placement and control
        # receipts; their original path names remain provenance, not inputs.
        for path,sha in saved['result']['files'].items():
            p=Path(path)
            if p.suffix in ('.json','.gz') or p.name.endswith('-freeze.log') or p.name.endswith('-restore.log'):
                name,hash_value=archive.file(p,sha);provenance[name]=hash_value
    validate_activity_coverage(activities)
    for name in ('config.json','code.json','grid.json','events.jsonl','inheritance-review.json','inherited-unloaded.json'):
        source=campaign/name
        if source.exists(): shutil.copyfile(source,destination/name)
    atomic(destination/'acquisition.json',dict(files=provenance,source=str(campaign),
        arrivals=sorted({t.get('arrival_model','fixed-spacing') for r in loaded for p in r['points'] for t in p['trials']}),
        arrival_coverage='Poisson deferred to memcached',
        loaded_boundary='call entry to RX-burst observation; 10 ms response deadline',
        unloaded_boundary='Tofino wire-to-wire CDF; CPU queue/protocol/handler breakdown with combined CPU-wire residual uses its own instrumented cohort'))
    output=h.directory/'plots'
    for script,source in (('plot_echo.py','loaded.json'),('plot_echo_unloaded.py','unloaded.json')):
        h.command([sys.executable,ROOT/'tools/paper'/script,'--input',destination/source,'--output',output],script,timeout=86400)
    previews=pdf_checks(output,['echo-loaded-hot-latency-goodput','echo-loaded-hot-peak-throughput-workers','echo-overload-loss',
                               'echo-unloaded-hot-latency-cdf','echo-unloaded-hot-latency-breakdown'])
    for name in ('echo-statistics.json','echo-trials.json','echo-unloaded-statistics.json','pdf-validation.json'):
        shutil.copyfile(output/name,destination/name)
    atomic(destination/'manifest.json',dict(status='complete',paper_inputs=True,
        loaded='loaded.json',unloaded='unloaded.json',provenance='acquisition.json',
        files={str(p.relative_to(destination)):digest(p) for p in destination.iterdir() if p.is_file()},
        visual_review='pending terminal callback'))
    from .adapter import stop_transfer
    stop_transfer(config,h)
    return dict(passed=True,complete=True,hashes=True,boundaries=True,failure_accounting=True,
        worker_activity=True,pdf_dimensions=True,embedded_fonts=True,previews=previews,
        files={str(p):digest(p) for p in destination.rglob('*') if p.is_file()})
