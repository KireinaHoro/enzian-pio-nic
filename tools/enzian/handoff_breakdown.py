#!/usr/bin/env python3
"""Extend cold activation evidence backwards through the outgoing yield syscall.

These intervals are nested in E2E queue residence, not additional RTT terms.
Generic syscall timestamps are quantized; DESCHED alignment uses the audited
CPU/FPGA epoch fit. Neither boundary isolates the CPU exception instruction.
"""
import argparse
import bisect
import hashlib
import json
import re
from pathlib import Path
from activation_breakdown import parse_trace, distribution
from ipg_breakdown import LINE


def yield_entries(text, command=0x800c4c0c):
    active, entries = {}, []
    for line in text.splitlines():
        match = LINE.search(line)
        if not match:
            continue
        tid, cpu, sec, fraction, event, fields = match.groups()
        tid, cpu = int(tid), int(cpu)
        stamp = int(sec)*10**9 + int(fraction.ljust(9, '0'))
        if event == 'sys_enter':
            if tid in active:
                raise ValueError('nested syscall entry for one TID')
            args = re.match(r'NR (\d+) \(([^)]*)\)', fields)
            if args and int(args[1]) == 29 and int(args[2].split(',')[1].strip(), 16) == command:
                row = dict(tid=tid, cpu=cpu, enter_ns=stamp, exit_ns=None)
                active[tid] = row
                entries.append(row)
        elif event == 'sys_exit' and tid in active:
            active.pop(tid)['exit_ns'] = stamp
    return entries


def join(audit, activations, timings, entries, events, cpu_base=44):
    by_request = {row['request_id']: row for row in activations}
    by_identity = {(row['worker'], row['sequence']): row for row in timings}
    preempts = {}
    for event in events:
        if event['event'] in ('SchedulerDeschedCore', 'SchedulerPreemptCore'):
            preempts.setdefault(event['data']['CoreID'], []).append(event)
    for values in preempts.values():
        values.sort(key=lambda row: row['time_ns'])
    times = {core: [row['time_ns'] for row in values] for core, values in preempts.items()}
    result = []
    for row in audit:
        activation = by_request[row['request_id']]
        if activation['activation_path'] != 'cooperative':
            raise ValueError('outgoing-domain analysis requires cooperative activation')
        timing = by_identity[(activation['worker'], activation['activation_sequence'])]
        begin = timing['read_begin']
        outgoing = [entry for entry in entries if entry['cpu'] == activation['worker']+cpu_base
                    and entry['enter_ns'] <= begin
                    and (entry['exit_ns'] is None or begin < entry['exit_ns'])
                    and entry['tid'] != activation['tid']]
        if len(outgoing) != 1:
            raise ValueError('missing/ambiguous outgoing yield syscall')
        outgoing = outgoing[0]
        core = row['datapath_core']
        start = row['trace_request_queued_ns']
        stop = begin-row['trace_cpu_offset_ns']
        lo, hi = bisect.bisect_left(times[core], start), bisect.bisect_right(times[core], stop)
        candidates = preempts[core][lo:hi]
        if len(candidates) != 1 or candidates[0]['data'].get('DeschedOutOfIdle', candidates[0]['data'].get('PreemptOutOfIdle', 0)):
            raise ValueError('missing/ambiguous cooperative FPGA DESCHED')
        event = candidates[0]
        preempt_cpu = event['time_ns']+row['trace_cpu_offset_ns']
        parts = {
            'queued_to_preempt_request': event['time_ns']-start,
            'preempt_request_to_outgoing_syscall_entry': outgoing['enter_ns']-preempt_cpu,
            'outgoing_syscall_entry_to_mmio': begin-outgoing['enter_ns'],
            **activation['activation_components_ns'],
        }
        if any(value < 0 for value in parts.values()):
            raise ValueError('negative interval; clock/quantization boundary is unresolved')
        total = sum(parts.values())
        expected = begin+activation['activation_total_ns']-(start+row['trace_cpu_offset_ns'])
        if abs(total-expected) > 1e-6:
            raise ValueError('handoff partition does not conserve')
        result.append(dict(request_id=row['request_id'], incoming_tid=activation['tid'],
                           outgoing_tid=outgoing['tid'], worker=activation['worker'],
                           previous_tgid=event['data']['PreviousProcessID'],
                           syscall_entry_ns=outgoing['enter_ns'],
                           preempt_request_fpga_ns=event['time_ns'],
                           read_begin_ns=begin,
                           incoming_tgid=event['data']['ProcessID'], components_ns=parts,
                           queue_to_rx_entry_ns=total))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('audit', 'activation', 'capture', 'events', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads((args.capture/'capture.json').read_text())
    if not metadata['loss_free'] or metadata['returncode'] or metadata['clock'] != 'mono_raw':
        raise ValueError('successful loss-free mono_raw capture required')
    audit = json.loads(args.audit.read_text())
    activation = json.loads(args.activation.read_text())
    if activation['audit_sha256'] != hashlib.sha256(args.audit.read_bytes()).hexdigest():
        raise ValueError('activation audit hash mismatch')
    text = (args.capture/'trace.txt').read_text()
    rows = join(audit['rows'], activation['rows'], parse_trace(text), yield_entries(text),
                json.loads(args.events.read_text())['events'])
    if len(rows) != len(audit['rows']):
        raise ValueError('missing requests')
    result = dict(rows=rows, count=len(rows), components_ns={
        key: distribution([row['components_ns'][key] for row in rows])
        for key in rows[0]['components_ns']},
        limitations=['Nested queue/handoff partition, not additional E2E components.',
                      'DESCHED-to-entry combines FPGA notification, outgoing runtime and kernel entry; no isolated exception-instruction measurement.',
                      'Syscall text timestamps have 1 us resolution; FPGA alignment uses the inferred audit offset.',
                      'Kernel tracing overhead retained; do not splice these values into untraced RTT.'],
        provenance={str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in
                    [args.audit, args.activation, args.capture/'trace.txt', args.capture/'capture.json', args.events]})
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(dict(count=len(rows), components_ns=result['components_ns']), indent=2))

if __name__ == '__main__':
    main()
