#!/usr/bin/env python3
"""Validate buffered hot-RPC PMU cohorts and export raw per-request stage counts.

No overhead subtraction: counter reads, timestamps and trace bookkeeping are
included. Cache refills may complete after their causative instruction. These
are user-only task counts; the RX call includes pre-arrival blocked time.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path

EVENTS = ('instructions', 'cycles', 'l1d_refill', 'l2d_refill')
LEGACY_STAGES = ('rx_enter', 'rx_exit', 'unmarshal_enter', 'unmarshal_exit',
          'handler_enter', 'handler_exit', 'marshal_enter', 'marshal_exit',
          'tx_enter', 'tx_exit')
LEGACY_SEGMENTS = ('rx_call_including_wait', 'runtime_dispatch', 'unmarshal',
            'before_handler', 'handler', 'before_marshal', 'marshal', 'before_tx', 'tx')


STAGES = ('rx_enter','rx_exit','handler_enter','handler_exit','tx_enter')
SEGMENTS = ('rx_call_including_wait','before_handler','handler','after_handler')

def percentiles(values):
    values = sorted(values)
    def p(q):
        x = (len(values)-1)*q/100
        lo = int(x)
        return values[lo] + (values[min(lo+1,len(values)-1)]-values[lo])*(x-lo)
    return {f'p{q}': p(q) for q in (50,90,95,99,100)}


def delta(row, start, end, exclude_instructions=False):
    result = {event: int(row[f'pmu_{end}_{i}']) - int(row[f'pmu_{start}_{i}'])
              for i,event in enumerate(EVENTS) if not (exclude_instructions and i==0)}
    if any(v < 0 for v in result.values()):
        raise ValueError('Nonmonotonic PMU count; cannot attribute this request')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    p.add_argument('--count', type=int, default=1000)
    p.add_argument('--warmup', type=int, default=16)
    p.add_argument('--exclude-instructions', action='store_true',
                   help='withhold an independently invalidated instruction event; retain all requests')
    args = p.parse_args()
    events = EVENTS[1:] if args.exclude_instructions else EVENTS
    b = args.directory
    paths = [b/'server/server.csv', b/'server/server.csv.status.json']
    status = json.loads(paths[1].read_text())
    n = args.count + args.warmup
    assert status['loss_free'] and status['dropped']==0 and status['rows']==3*n, status
    rows = list(csv.DictReader(paths[0].open()))
    assert len(rows)==3*n
    summary, exported = {}, []
    for mode, phase in enumerate(('off','coarse','detailed')):
        path = b/f'{phase}.csv'; paths.append(path)
        clients = list(csv.DictReader(path.open()))
        servers = rows[mode*n:(mode+1)*n]
        assert len(clients)==n
        measurements = []
        for index,(r,c) in enumerate(zip(servers,clients)):
            assert r['request_id']==c['request_id']
            assert r['ok']==c['ok']=='1' and r['request_id_valid']=='1'
            assert r['execution_kind']=='0' and r['reply_status']=='0' and r['transport_status']=='1'
            assert r['rx_cpu_id']==r['tx_cpu_id'] and r['worker_id']==r['tx_worker_id']
            current = int(r.get('accounting_version',3)) == 4
            stages,segments = (STAGES,SEGMENTS) if current else (LEGACY_STAGES,LEGACY_SEGMENTS)
            endpoint = 'tx_enter' if current else 'tx_exit'
            assert int(r['pmu_mode'])==mode and int(r['pmu_mask'])==((0,37,61) if current else (0,1029,2045))[mode]
            assert int(c['warmup'])==int(index<args.warmup)
            if index < args.warmup:
                continue
            m = {'rtt_ns': int(c['client_call_exit_ns'])-int(c['client_call_enter_ns'])}
            records = {}
            if mode:
                records['rx_call_including_wait'] = delta(r,'rx_enter','rx_exit',args.exclude_instructions)
                records['service_after_rx'] = delta(r,'rx_exit',endpoint,args.exclude_instructions)
                records['rx_enter_to_'+endpoint] = delta(r,'rx_enter',endpoint,args.exclude_instructions)
            if mode==2:
                records.update({name:delta(r,start,end,args.exclude_instructions)
                                for name,start,end in zip(segments,stages,stages[1:])})
                for event in events:
                    assert sum(records[name][event] for name in segments[1:]) == records['service_after_rx'][event]
            for component,counts in records.items():
                exported.append(dict(phase=phase,request_id=r['request_id'],
                    cpu=r['rx_cpu_id'],worker=r['worker_id'],component=component,**counts))
                m.update({f'{component}.{event}':value for event,value in counts.items()})
            measurements.append(m)
        assert len(measurements)==args.count
        summary[phase] = {k:percentiles([m[k] for m in measurements]) for k in measurements[0]}
    with (b/'per-request.csv').open('w') as f:
        w=csv.DictWriter(f,fieldnames=exported[0]);w.writeheader();w.writerows(exported)
    result = dict(count_per_cohort=args.count,warmup_per_cohort=args.warmup,
        counter_events=dict(zip(EVENTS,['0x08','0x11','0x03','0x17'])),
        user_only=True,raw_instrumented_counts=True,multiplexing=False,
        excluded_events=['instructions'] if args.exclude_instructions else [],
        quantiles=summary,input_sha256={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in paths})
    (b/'pmu-summary.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({phase:{k:v for k,v in values.items() if k=='rtt_ns' or k.startswith('service_after_rx.')}
                      for phase,values in summary.items()},indent=2))


if __name__=='__main__':
    main()
