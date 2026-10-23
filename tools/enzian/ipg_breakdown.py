#!/usr/bin/env python3
"""Explain runtime TX-exit -> next RX-entry gaps using a mono_raw kernel capture.
Components partition the gap; they are diagnostics, not extra E2E components.
Only complete paired intervals are credited. Boundary gaps remain unattributed.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re

LINE = re.compile(r'-(\d+)\s+\[(\d+)\]\s+\S+\s+(\d+)\.(\d+):\s+(\w+):\s+(.*)')
PRIORITY = ('off_cpu', 'irq', 'ipi', 'softirq', 'syscall')
PAIRS = {'sys_enter': ('syscall', True), 'sys_exit': ('syscall', False),
         'irq_handler_entry': ('irq', True), 'irq_handler_exit': ('irq', False),
         'softirq_entry': ('softirq', True), 'softirq_exit': ('softirq', False),
         'ipi_entry': ('ipi', True), 'ipi_exit': ('ipi', False)}


def intervals(text):
    if re.search(r'LOST\s+\d+\s+EVENTS', text):
        raise ValueError('lost kernel events')
    active = {}; off = {}; result = []; incomplete = 0
    for line in text.splitlines():
        match = LINE.search(line)
        if not match:
            if any(re.search(r': '+e+r':',line) for e in [*PAIRS, 'sched_switch']):
                raise ValueError('unrecognized kernel event line')
            continue
        tid, cpu, sec, fraction, event, fields = match.groups()
        tid, cpu = int(tid), int(cpu)
        if len(fraction)>9: raise ValueError('unsupported timestamp precision')
        stamp = int(sec)*10**9 + int(fraction.ljust(9,'0'))
        if event == 'sched_switch':
            prev = re.search(r'prev_pid=(\d+)',fields)
            nxt = re.search(r'next_pid=(\d+)',fields)
            if not prev or not nxt: raise ValueError('missing switch identity')
            prev,nxt=int(prev[1]),int(nxt[1])
            if prev:
                if prev in off: raise ValueError('duplicate switch out')
                off[prev]=stamp
            if nxt in off:
                start=off.pop(nxt)
                if stamp<start: raise ValueError('reversed switch interval')
                result.append((start,stamp,nxt,'off_cpu'))
            continue
        if event not in PAIRS: continue
        kind,enter = PAIRS[event]
        # Syscalls follow a TID across CPUs; interrupts are paired per CPU/kind.
        key=(kind,tid if kind=='syscall' else cpu)
        stack=active.setdefault(key,[])
        if enter:
            stack.append((stamp,tid))
        elif stack:
            start,owner=stack.pop()
            if stamp<start or owner!=tid: raise ValueError('invalid kernel event pair')
            result.append((start,stamp,tid,kind))
        else:
            incomplete+=1
    incomplete += sum(len(stack) for stack in active.values()) + len(off)
    return result,incomplete


def partition(start,end,tid,spans):
    if end<start: raise ValueError('reversed IPG')
    clipped=[(max(start,a),min(end,b),kind) for a,b,t,kind in spans
             if t==tid and a<end and b>start]
    points=sorted({start,end,*[x for a,b,_ in clipped for x in (a,b)]})
    totals=dict.fromkeys((*PRIORITY,'unattributed_ipg'),0)
    for a,b in zip(points,points[1:]):
        present={kind for lo,hi,kind in clipped if lo<=a and hi>=b}
        category=next((k for k in PRIORITY if k in present),'unattributed_ipg')
        totals[category]+=b-a
    assert sum(totals.values())==end-start
    return totals


def breakdown(rows,spans):
    by_tid={}; output=[]
    for row in rows:
        if int(row.get('accounting_version',3))>=4:
            raise ValueError('Post-TX-return IPG analysis requires archived return timestamps; version 4 ends at publication')
        if int(row.get('execution_kind',0)):
            raise ValueError('IPG analysis requires inline serial RPCs')
        by_tid.setdefault(int(row['server_tid']),[]).append(row)
    for tid,group in by_tid.items():
        group.sort(key=lambda r:int(r['server_rx_enter_ns']))
        for previous,row in zip(group,group[1:]):
            start=int(previous['server_tx_exit_ns']); end=int(row['server_rx_enter_ns'])
            if end<start: raise ValueError('overlapping requests: IPG analysis requires inline serial RPCs')
            if previous.get('tx_cpu_id')!=row.get('rx_cpu_id'):
                raise ValueError('worker migrated across IPG; full CPU coverage must be established first')
            output.append(dict(request_id=row['request_id'],tid=tid,start_ns=start,end_ns=end,
                               gap_ns=end-start,components_ns=partition(start,end,tid,spans)))
    return output


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--capture',type=Path,required=True)
    p.add_argument('--server',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args(); meta=json.loads((a.capture/'capture.json').read_text())
    if not meta.get('loss_free') or meta.get('returncode')!=0 or meta.get('clock')!='mono_raw':
        p.error('requires successful loss-free mono_raw IPG capture')
    from activation_trace import IPG_EVENTS
    if not set(IPG_EVENTS)<=set(meta.get('events',[])): p.error('missing required IPG events')
    status=json.loads(Path(str(a.server)+'.status.json').read_text())
    if not status.get('loss_free'): p.error('runtime buffer overflow or export failure')
    with a.server.open() as f: rows=list(csv.DictReader(f))
    if len(rows)!=status['rows']: p.error('runtime export row count mismatch')
    if any(int(r[k]) not in meta['cpus'] for r in rows for k in ('rx_cpu_id','tx_cpu_id')):
        p.error('runtime CPU outside kernel capture coverage')
    trace=a.capture/'trace.txt'; spans,incomplete=intervals(trace.read_text())
    result=dict(rows=breakdown(rows,spans),incomplete_intervals=incomplete,
                timestamp_quantum_ns=meta['timestamp_quantum_ns'],
                limitations=meta['limitations']+['IPG diagnostics are not additive E2E components',
                    'incomplete pairs receive no attribution; first request has no preceding TX',
                    'only IPG overlap after request arrival can contribute to that request RTT'],
                provenance={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in
                            (trace,a.server,a.capture/'capture.json')})
    a.output.write_text(json.dumps(result,indent=2)+'\n')

if __name__=='__main__': main()
