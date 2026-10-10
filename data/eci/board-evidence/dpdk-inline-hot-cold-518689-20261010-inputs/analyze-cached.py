import sys,json,hashlib,importlib.util,inspect
from pathlib import Path
spec=importlib.util.spec_from_file_location('latency','data/eci/apps/plot_latency.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
original=m.decode_trace_events
def cached(path,map_path,cycle,window):
    cache=path.parent/'latency-events.json'
    key={'raw_sha256':hashlib.file_digest(path.open('rb'),'sha256').hexdigest(),'map_sha256':hashlib.sha256(map_path.read_bytes()).hexdigest(),'cycle':cycle,'window':window,'decoder':hashlib.sha256(inspect.getsource(original).encode()).hexdigest()}
    if cache.exists():
        c=json.loads(cache.read_text())
        if c['key']==key:return c['events'],c['rtt']
    events,rtt=original(path,map_path,cycle,window)
    cache.write_text(json.dumps(dict(key=key,events=events,rtt=rtt)))
    print('DECODE_CACHE_SAVED',len(events),flush=True)
    return events,rtt
m.decode_trace_events=cached
attach=m.attach_trace_segments
def debug_attach(row,rec,index,*args):
    try:return attach(row,rec,index,*args)
    except ValueError as exc:
        center=rec.get('RxRpcEnqueueToHost',0)
        nearby={name:[e for e in values[1] if abs(e['time_ns']-center)<2000] for name,values in index.items() if name in ('RxCmacEntry','RxAfterCdcQueue','EthernetDecoder','IpDecoder','UdpDecoder','OncRpcCallDecoder','RxRpcEnqueueToHost')}
        Path(sys.argv[1]+'.correlation-error.json').write_text(json.dumps(dict(error=str(exc),row=row,rec=rec,nearby=nearby),indent=2))
        raise
m.attach_trace_segments=debug_attach
raise SystemExit(m.main())
