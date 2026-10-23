"""Unloaded software intervals and external wire-duration integration.

No FPGA MAC extraction or NIC hardware timestamp path is used here. Missing
wire durations are explicit readiness-only results, rejected by paper validators.
"""
import csv
import gzip
import json
from .import_loaded import digest
from .policy import SIZES, SYSTEMS
from .wire import COMPONENTS, METHOD, join_durations
from . import accounting

def rows(path):
    opener=gzip.open if str(path).endswith('.gz') else open
    with opener(path,'rt') as stream: return list(csv.DictReader(stream))


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


def clock_bounds(bounds):
    # Fit only a feasibility bound; never transform the measured CPU intervals.
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


def cpu_partition(start, end, intervals):
    ordered=sorted(intervals.values())
    if start<=0 or end<=start or any(not start<=a<=b<=end for a,b in ordered):
        raise ValueError('Invalid CPU stage boundaries')
    if any(b>c for (_,b),(c,_) in zip(ordered,ordered[1:])):
        raise ValueError('Overlapping CPU components')
    parts=dict.fromkeys(COMPONENTS,0)
    parts.update({k:b-a for k,(a,b) in intervals.items()})
    parts['cpu_other']=end-start-sum(parts.values())
    return dict(cpu_duration_ns=end-start,components_ns=parts)


def finish(client_path, client, software, *, system, count, warmup, instrumentation,
           payload_bytes, wire_path=None, server_path=None, correlation='wire XID'):
    keys=[dict(flow_id=0,request_id=int(r['request_id']),rtt_ns=int(r['client_rx_burst_ns'])-int(r['client_call_enter_ns'])) for r in client]
    files={str(client_path):digest(client_path)}
    if server_path: files[str(server_path)]=digest(server_path)
    if wire_path:
        raw=json.loads(open(wire_path).read())
        if raw.get('methodology')!=METHOD or raw.get('fixture') is not False:
            raise ValueError('Wire capture is not validated acquisition evidence')
        observations=join_durations(keys,raw['rows'],software)
        files[str(wire_path)]=digest(wire_path)
    else:
        observations=[dict(flow_id=r['flow_id'],request_id=r['request_id'],wire_ns=None) for r in keys]
        if software:
            for row,sw in zip(observations,software): row.update({k:sw[k] for k in ('cpu_duration_ns','components_ns')})
    if software:
        for row, sw in zip(observations, software):
            if 'accounting' in sw: row['accounting'] = sw['accounting']
    for row,cl in zip(observations,client): row['warmup']=int(cl['warmup'])
    return dict(system=system,methodology=METHOD,wire_available=wire_path is not None,
                paper_inputs=wire_path is not None,instrumentation=instrumentation,
                count=count,warmup=warmup,payload_bytes=payload_bytes,correlation=correlation,
                rows=observations,files=files)


def dpdk(client_path, server_path, *, count, warmup, instrumentation, payload_bytes, wire_path=None):
    client=client_rows(client_path,count,warmup,payload_bytes);server=rows(server_path)
    by_id={int(r['request_id']):r for r in server}
    if len(by_id)!=len(server) or set(by_id)!={int(r['request_id']) for r in client}:
        raise ValueError('Duplicate or mismatched DPDK XID sets')
    software=[]
    partition_version=accounting.version(server) if instrumentation=='breakdown' else None
    for cl in client:
        sw=by_id[int(cl['request_id'])]
        if sw['status']!='1' or sw['warmup']!=cl['warmup'] or int(sw['profile'])!=int(instrumentation=='breakdown'):
            raise ValueError('Invalid DPDK status/instrumentation')
        if instrumentation=='breakdown':
            t=lambda k:int(sw[k+'_ns'])
            stages=['rx_poll_enter','rx_cqe','rx_return','network_rx_begin','network_rx_end',
                    'rpc_rx_end','handler_end','rpc_tx_end','network_tx_begin','network_tx_end',
                    'tx_submit_begin','tx_pmd_enter','tx_doorbell','tx_return']
            times=[t(k) for k in stages]
            if not times[0] or times!=sorted(times): raise ValueError('Invalid queue/software stages')
            part=cpu_partition(t('rx_cqe'),t('tx_return'),{
                'rx_queue':(t('rx_cqe'),t('rx_return')),
                'rx_network':(t('network_rx_begin'),t('network_rx_end')),
                'rpc_receive':(t('network_rx_end'),t('rpc_rx_end')),
                'handler':(t('rpc_rx_end'),t('handler_end')),
                'rpc_transmit':(t('handler_end'),t('rpc_tx_end')),
                'tx_network':(t('network_tx_begin'),t('network_tx_end')),
                'tx_submission':(t('network_tx_end'),t('tx_return'))})
            if partition_version==3: part=accounting.partition(sw,'DPDK')
            part.update(flow_id=0,request_id=int(cl['request_id']))
            software.append(part)
    result=finish(client_path,client,software if instrumentation=='breakdown' else None,
        system='DPDK',count=count,warmup=warmup,instrumentation=instrumentation,
        payload_bytes=payload_bytes,wire_path=wire_path,server_path=server_path)
    if software:
        result['cpu_partition_version']=partition_version
        result['rx_preparation_ns']=[int(by_id[int(c['request_id'])]['rx_cqe_ns'])-int(by_id[int(c['request_id'])]['rx_poll_enter_ns']) for c in client]
        result['rx_preparation_scope']='Successful poll before CQE observation; outside CPU critical interval'
    return result


def correct_cpu_submission(cohort):
    """Reclassify saved v1 intervals from hashed raw timestamps; preserve raw receipts."""
    if (cohort['instrumentation']!='breakdown'
            or cohort.get('cpu_partition_version',1)>=2): return
    from pathlib import Path
    by_chunk={}
    for chunk in cohort.get('chunks',[]):
        path=Path(chunk['raw_capture']['path']).parent.parent/'server-slice.csv'
        if cohort['files'].get(str(path))!=digest(path):
            raise ValueError('Missing or changed raw submission boundaries')
        by_chunk[chunk['index']]={i:r for i,r in enumerate(rows(path),1)}
    if not by_chunk: raise ValueError('Reimport legacy non-chunked CPU intervals from raw server rows')
    for row in cohort['rows']:
        raw=by_chunk[row['chunk']][row['wire_xid']]
        delta=(int(raw['tx_return_ns'])-int(raw['tx_doorbell_ns']) if cohort['system']=='DPDK'
               else int(raw['server_tx_doorbell_ns'])-int(raw['server_tx_enter_ns']))
        parts=row['components_ns']
        if not 0<=delta<=parts['cpu_other']: raise ValueError('Invalid submission reclassification interval')
        parts['tx_submission']+=delta
        parts['cpu_other']-=delta
    cohort['cpu_partition_version']=2


def lauberhorn(client_path, *, count, warmup, setup_count, instrumentation,
               payload_bytes, server_path=None, wire_path=None, priming_count=0):
    client=client_rows(client_path,count,warmup,payload_bytes);software=[];bounds=[]
    if instrumentation=='breakdown':
        server=rows(server_path)
        if len(server)!=setup_count+len(client): raise ValueError('Incomplete runtime cohort')
        partition_version=accounting.version(server)
        previous=0
        for cl,sw in zip(client,server[setup_count:]):
            t=lambda k:int(sw['server_'+k+'_ns'])
            if sw['ok']!='1' or t('rx_exit')<previous: raise ValueError('Invalid runtime reply/order')
            previous=t('tx_exit')
            part=cpu_partition(t('rx_exit'),t('tx_exit'),{
                'rpc_receive':(t('unmarshal_enter'),t('handler_enter')),
                'handler':(t('handler_enter'),t('handler_exit')),
                'rpc_transmit':(t('marshal_enter'),t('marshal_exit')),
                'tx_submission':(t('tx_enter'),t('tx_doorbell_return'))})
            if partition_version==3: part=accounting.partition(sw,'LH')
            part.update(flow_id=0,request_id=int(cl['request_id']));software.append(part)
            bounds.append((t('rx_unblock') if partition_version==3 else t('rx_exit'),t('tx_exit'),int(cl['client_call_enter_ns']),int(cl['client_rx_burst_ns'])))
    result=finish(client_path,client,software if instrumentation=='breakdown' else None,
        system='LH',count=count,warmup=warmup,instrumentation=instrumentation,
        payload_bytes=payload_bytes,wire_path=wire_path,server_path=server_path,
        correlation='Exact single-outstanding runtime order; client wire XID validation')
    if bounds:
        result['causal_cpu_client']=clock_bounds(bounds)
        result['cpu_partition_version']=partition_version
    return result


def validate_matched(cohorts, *, rehearsal=False, systems=SYSTEMS, sizes=SIZES):
    if not sizes or not set(sizes)<=set(SIZES):
        raise ValueError('Invalid unloaded payload selection')
    expected={(s,n,m) for s in systems for n in sizes for m in ('off','breakdown')}
    if len(cohorts)!=len(expected) or {(c['system'],c['payload_bytes'],c['instrumentation']) for c in cohorts}!=expected:
        raise ValueError('Incomplete unloaded cohort matrix')
    versions={c.get('cpu_partition_version',1) for c in cohorts if c['instrumentation']=='breakdown'}
    if 3 in versions and versions!={3}:
        raise ValueError('Cannot compare schema-3 codec accounting with legacy coarse RPC accounting')
    for system in systems:
        builds={m:{c['build_config_sha256'] for c in cohorts if c['system']==system and c['instrumentation']==m} for m in ('off','breakdown')}
        if any(len(v)!=1 for v in builds.values()) or builds['off']==builds['breakdown']:
            raise ValueError('Expected distinct, consistent compiled tracing variants')
    for c in cohorts:
        if c.get('methodology')!=METHOD or c.get('wire_available') is not True or (not rehearsal and c.get('paper_inputs') is not True):
            raise ValueError('Missing Tofino wire durations; readiness results are not paper inputs')
        if len(c['rows'])!=c['count']+c['warmup']: raise ValueError('Incomplete unloaded observations')
        expected_warmups=[int(i<c['warmup']) for i in range(len(c['rows']))]
        if c.get('chunks'):
            expected_warmups=[]
            if sum(x['count'] for x in c['chunks'])!=c['count'] or c['warmup']!=sum(x['warmup'] for x in c['chunks']):
                raise ValueError('Invalid chunk totals')
            offset=0
            for index,chunk in enumerate(c['chunks']):
                warmup=chunk['warmup']
                if (chunk['index']!=index or type(warmup) is not int or not 0<=warmup<1024
                        or not 0<chunk['count']<=1024-warmup):
                    raise ValueError('Invalid capture chunk')
                for xid in range(1,chunk['count']+warmup+1):
                    row=c['rows'][offset];offset+=1
                    if row['chunk']!=index or row['wire_xid']!=xid:raise ValueError('Invalid chunk wire identity')
                    expected_warmups.append(int(xid<=warmup))
        for i,row in enumerate(c['rows'],1):
            if row['request_id']!=i or row['warmup']!=expected_warmups[i-1] or not row.get('wire_ns',0)>0:
                raise ValueError('Invalid unloaded identity, warmup or wire duration')
            if c['instrumentation']=='breakdown':
                parts=row['components_ns']
                if (set(parts)!=set(COMPONENTS)|{'cpu_wire'}
                        or any(type(v) is not int for v in parts.values())
                        or any(parts[k]<0 for k in COMPONENTS)
                        or sum(parts.values())!=row['wire_ns']
                        or row.get('cpu_duration_ns',sum(parts[k] for k in COMPONENTS))
                           !=sum(parts[k] for k in COMPONENTS)):
                    raise ValueError('Invalid CPU partition or signed wire-minus-CPU difference')
            elif 'components_ns' in row: raise ValueError('Components attached to uninstrumented cohort')


def validate_request(cohort, request):
    """Request warmup is per rearm; cohort warmup counts all archived warmups."""
    if any(cohort[k]!=request[k] for k in ('system','payload_bytes','count','instrumentation')):
        raise ValueError('Unloaded cohort differs from requested configuration')
    chunks=cohort.get('chunks')
    if chunks:
        from .chunked import plan
        expected=plan(request['count'],request['warmup'])
        if ([c['count'] for c in chunks]!=expected or
                any(c['index']!=i or c['warmup']!=request['warmup'] for i,c in enumerate(chunks))):
            raise ValueError('Unloaded chunks differ from requested capture plan')
        warmup=request['warmup']*len(expected)
    else:
        if request['count']+request['warmup']>1024:
            raise ValueError('Missing unloaded capture chunks')
        warmup=request['warmup']
    if cohort['warmup']!=warmup:
        raise ValueError('Unloaded warmup total differs from requested capture plan')
