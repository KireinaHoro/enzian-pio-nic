"""Validate single-outstanding schema-defined echo cohorts without relabeling historical add data.

LH input is the complete MAC-pair table from import_nullrpc_mac.correlate;
its single-outstanding association is independently checked with causal clock
bounds. DPDK timestamps and client replies correlate directly on wire XID.
"""
import csv
import gzip
import json
from pathlib import Path
from .import_loaded import digest
from .policy import SIZES


def rows(path):
    opener=gzip.open if str(path).endswith('.gz') else open
    with opener(path,'rt') as stream: return list(csv.DictReader(stream))


def partition(total, handler, rpc, network):
    values=dict(handler=handler,rpc=rpc,network=network,host_mac=total-handler-rpc-network)
    if total<=0 or any(v<0 for v in values.values()):
        raise ValueError('Invalid service-path elapsed-time partition')
    return values


def client_rows(path, count, warmup, payload_bytes):
    if payload_bytes not in (0, *SIZES): raise ValueError("Unknown schema echo size")
    client=rows(path)
    if len(client)!=count+warmup: raise ValueError('Incomplete unloaded client cohort')
    previous_rx=0
    for i,row in enumerate(client,1):
        if (int(row['request_id'])!=i or row['benchmark']!='echo'+str(payload_bytes) or row['ok']!='1'
                or row['rpc_status']!='0' or int(row['warmup'])!=int(i<=warmup)):
            raise ValueError('Wrong workload, XID, status or warmup label')
        enter,rx=int(row['client_call_enter_ns']),int(row['client_rx_burst_ns'])
        if not previous_rx<=enter<rx: raise ValueError('Client was not single-outstanding')
        previous_rx=rx
    return client


def dpdk(client_path, server_path, *, count, warmup, instrumentation, payload_bytes):
    client=client_rows(client_path,count,warmup,payload_bytes); server=rows(server_path)
    if len(server)!=len(client): raise ValueError('Incomplete DPDK MAC cohort')
    by_id={int(r['request_id']):r for r in server}
    if len(by_id)!=len(server): raise ValueError('Duplicate DPDK XID')
    if set(by_id)!={int(r['request_id']) for r in client}:
        raise ValueError('DPDK/client XID sets differ')
    result=[]
    for cl in client:
        xid=int(cl['request_id']); sw=by_id[xid]
        if (sw['status']!='1' or sw['warmup']!=cl['warmup'] or
                int(sw['profile'])!=int(instrumentation=='breakdown') or int(sw['injected_delay_ns'])):
            raise ValueError('Invalid DPDK instrumentation/status')
        rx,tx=int(sw['rx_hw_ns']),int(sw['tx_hw_ns']); total=tx-rx
        if not 0<total<=int(cl['client_rx_burst_ns'])-int(cl['client_call_enter_ns']):
            raise ValueError('Invalid DPDK MAC interval')
        record=dict(request_id=xid,warmup=int(cl['warmup']),rx_mac_ns=rx,tx_mac_ns=tx,mac_ns=total)
        if instrumentation=='breakdown':
            times=[int(sw[k]) for k in ('cpu_rx_ns','network_rx_begin_ns','network_rx_end_ns',
                'network_tx_begin_ns','network_tx_end_ns','cpu_tx_ns')]
            if times!=sorted(times) or not times[0]: raise ValueError('Invalid DPDK service stages')
            a,b,c,d,e,f=times
            record['components_ns']=partition(total,d-c,(b-a)+(f-e),(c-b)+(e-d))
        result.append(record)
    return dict(system='DPDK',instrumentation=instrumentation,count=count,warmup=warmup,payload_bytes=payload_bytes,
                correlation='wire XID',rows=result,
                files={str(p):digest(p) for p in (client_path,server_path)})


def clock_bounds(bounds):
    # Fit only a feasibility bound; never transform the measured MAC intervals.
    import numpy as np
    from scipy.optimize import linprog
    values=np.asarray(bounds,dtype=float)
    if values.ndim!=2 or values.shape[1]!=4 or not len(values) or not np.isfinite(values).all():
        raise ValueError('Invalid causal clock bounds')
    if np.any(values[:,0]>values[:,1]) or np.any(values[:,2]>values[:,3]):
        raise ValueError('Reversed causal interval')
    x0=values[0,0]; base=values[0,2]-x0
    rx=(values[:,0]-x0)/1e9; tx=(values[:,1]-x0)/1e9
    fit=linprog([0,0],A_ub=np.vstack([np.column_stack([-np.ones(len(values)),-rx]),
                                     np.column_stack([np.ones(len(values)),tx])]),
                b_ub=np.r_[(values[:,0]+base-values[:,2])/1000,
                           (values[:,3]-values[:,1]-base)/1000],
                bounds=[(None,None),(-1000,1000)],method='highs')
    if not fit.success: raise ValueError('Single-outstanding association violates causal clock bounds')
    return dict(offset_us=float(fit.x[0]),rate_ppm=float(fit.x[1]),applied_to_measurements=False)

def lauberhorn(client_path, mac_pairs, *, count, warmup, setup_count,
               instrumentation, payload_bytes, server_path=None, app_events=None, priming_count=0):
    client=client_rows(client_path,count,warmup,payload_bytes)
    if len(mac_pairs)!=setup_count+len(client): raise ValueError('Incomplete whole-capture RPC accounting')
    calls=mac_pairs[setup_count:]
    if priming_count not in (0,1) or priming_count>setup_count:
        raise ValueError('Invalid neighbor priming boundary')
    direct=mac_pairs[priming_count:]
    if any(r['tx_mac_ns'] is None for r in direct): raise ValueError('Unpaired MAC event')
    if any(int(r['tx_mac_ns'])<=int(r['rx_mac_ns']) for r in direct):
        raise ValueError('Invalid whole-capture MAC interval')
    if any(int(a['tx_mac_ns'])>int(b['rx_mac_ns']) for a,b in zip(direct,direct[1:])):
        raise ValueError('Overlapping whole-capture unloaded requests')
    bounds=[]; runtime_bounds=[]; result=[]
    server=rows(server_path) if server_path else []
    if instrumentation=='breakdown' and (len(server)!=len(mac_pairs) or not app_events):
        raise ValueError('Breakdown needs the matched runtime rows and app-clock events')
    for i,(cl,mac) in enumerate(zip(client,calls)):
        rx,tx=int(mac['rx_mac_ns']),int(mac['tx_mac_ns']); total=tx-rx
        enter,leave=int(cl['client_call_enter_ns']),int(cl['client_rx_burst_ns'])
        if not 0<total<=leave-enter: raise ValueError('Invalid LH MAC interval')
        if i and calls[i-1]['tx_mac_ns']>rx: raise ValueError('Overlapping unloaded MAC requests')
        bounds.append((rx,tx,enter,leave))
        record=dict(request_id=int(cl['request_id']),warmup=int(cl['warmup']),rx_mac_ns=rx,tx_mac_ns=tx,mac_ns=total)
        if instrumentation=='breakdown':
            import bisect
            stages=[]
            for eid in (3,4,9,44,45,2,5,6):
                events=app_events[eid]
                selected=events[bisect.bisect_left(events,rx):bisect.bisect_right(events,tx)]
                if len(selected)!=1: raise ValueError('Nonunique/missing hardware service stage')
                stages.append(selected[0])
            if stages!=sorted(stages) or stages[0]!=rx or stages[-1]!=tx:
                raise ValueError('Invalid app-clock stage ordering')
            sw=server[setup_count+i]
            if sw['ok']!='1': raise ValueError('Runtime reply failed')
            a,b,c,d=[int(sw[k]) for k in ('server_rx_exit_ns','server_handler_enter_ns',
                                         'server_handler_exit_ns','server_tx_enter_ns')]
            if not a<=b<=c<=d: raise ValueError('Invalid runtime service stages')
            _,after_rx,udp_rx,to_host,from_host,udp_tx,before_tx,_=stages
            runtime_bounds.append((a,d,to_host,from_host))
            record['components_ns']=partition(total,c-b,d-a-(c-b)+to_host-udp_rx+udp_tx-from_host,
                                              udp_rx-after_rx+before_tx-udp_tx)
        result.append(record)
    causal=dict(client_mac=clock_bounds(bounds))
    if runtime_bounds: causal['runtime_mac']=clock_bounds(runtime_bounds)
    files={str(client_path):digest(client_path)}
    if server_path: files[str(server_path)]=digest(server_path)
    return dict(system='LH',instrumentation=instrumentation,count=count,warmup=warmup,payload_bytes=payload_bytes,
                correlation='XID-validated single-outstanding order plus causal bounds',
                rows=result,files=files,causal=causal,
                preparation=dict(priming_count=priming_count,setup_count=setup_count,
                                 excluded_from_observations=True,mac_pairs=mac_pairs[:setup_count]))


def validate_matched(cohorts):
    """Instrumented components never borrow an uninstrumented total."""
    expected={(s,n,m) for s in ('LH','DPDK') for n in SIZES for m in ('off','breakdown','probe-off-control')}
    if len(cohorts)!=len(expected) or {(c['system'],c['payload_bytes'],c['instrumentation']) for c in cohorts}!=expected:
        raise ValueError('Incomplete unloaded cohort matrix')
    for system in ('LH','DPDK'):
        selected=[c for c in cohorts if c['system']==system]
        if len({c['build_config_sha256'] for c in selected})!=1:
            raise ValueError('Instrumentation controls must use the same build/configuration')
    for c in cohorts:
        if len(c['rows'])!=c['count']+c['warmup']: raise ValueError('Incomplete unloaded observations')
        for i,row in enumerate(c['rows'],1):
            if row['request_id']!=i or row['warmup']!=int(i<=c['warmup']) or row['mac_ns']<=0 or row['mac_ns']!=row['tx_mac_ns']-row['rx_mac_ns']:
                raise ValueError('Invalid unloaded identity, warmup or MAC interval')
        if c['instrumentation']=='breakdown':
            for row in c['rows']:
                if any(v<0 for v in row['components_ns'].values()):
                    raise ValueError('Negative service-path component')
                if sum(row['components_ns'].values())!=row['mac_ns']:
                    raise ValueError('Instrumented components do not conserve their own total')
        elif any('components_ns' in row for row in c['rows']):
            raise ValueError('Components attached to an uninstrumented cohort')
