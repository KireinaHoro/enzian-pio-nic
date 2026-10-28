#!/usr/bin/env python3
"""Plot actual median-nearest RPC waterfalls and all component distributions.

Inputs are strict plot_latency audits, activation/handoff evidence and matched
raw traces. No synthetic median stack or reconstruction of residual direction.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import sys
os.environ.setdefault('MPLCONFIGDIR', '/tmp/lauberhorn-matplotlib')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
from matplotlib.lines import Line2D
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT/'tools/enzian'))
import analyze_rx_holds as h
from activation_breakdown import parse_trace

LABELS = {
 'lh_rx_cmac_to_cdc':'RX MAC → CDC', 'lh_rx_eth_decode':'RX Ethernet decode',
 'lh_rx_ip_decode':'RX IP decode', 'lh_rx_udp_decode':'RX UDP decode',
 'lh_rx_rpc_decode_and_host_enqueue':'RPC decode + host enqueue',
 'lh_scheduler_enqueue':'Scheduler enqueue', 'lh_scheduler_queue':'Queue / reactivation wait',
 'lh_rx_eci_delivery':'Dispatch → RX descriptor', 'lh_rx_2f2f_hw':'RX 2F2F hardware estimate',
 'server_2f2f_rx_sw':'RX software', 'server_rt_overhead':'Runtime overhead',
 'server_xdr_unmarshal':'Unmarshal', 'handler':'Handler', 'server_xdr_marshal':'Marshal',
 'server_2f2f_tx_sw':'TX preparation (legacy: includes doorbell)',
 'lh_rx_desc_to_dcs_response':'RX descriptor → DCS response',
 'lh_tx_cpu_to_dcs_request':'CPU → DCS request (estimate)',
 'lh_tx_dcs_request_to_commit':'DCS request → commit',
 'lh_tx_doorbell_arrival':'TX doorbell arrival', 'lh_tx_2f2f_hw':'TX 2F2F hardware',
 'lh_tx_host_submit':'TX completion → submit', 'lh_tx_submit_to_udp_encode':'TX submit → UDP encode',
 'lh_tx_ip_encode':'TX IP encode', 'lh_tx_eth_encode':'TX Ethernet encode',
 'lh_tx_output_queue':'TX output queue', 'lh_tx_cdc_to_cmac':'TX CDC → MAC',
 'lh_unattributed':'Unattributed inside MAC interval', 'client_packet_prepare':'Client packet preparation',
 'client_reply_validate':'Client reply validation', 'outside_lh_client_network':'External residual',
 'queued_to_preempt_request':'Queue → DESCHED request',
 'preempt_request_to_outgoing_syscall_entry':'DESCHED request → outgoing syscall entry',
 'outgoing_syscall_entry_to_mmio':'Outgoing syscall entry → MMIO',
 'preempt_info_mmio':'Desched-info MMIO', 'claim_wait':'Claim successor',
 'affinity_setup':'Set affinity', 'activation_publish':'Publish activation',
 'wake_to_wait_return':'Wake → incoming wait returns', 'yield_copyout':'Yield copyout',
 'kernel_to_userspace':'Kernel → incoming userspace',
 'runtime_activation_setup':'Runtime activation setup', 'runtime_before_rx':'Runtime before RX',
 'client_tx_submit_observed_ns':'Client TX submission (overlapping)',
 'client_poll_wait_observed_ns':'Client poll wait (overlapping)',
 'trace_lh_cmac_to_cmac_ns':'RX MAC → TX MAC',
 'trace_tx_commit_to_submit_ns':'TX commit → submit (overlapping)',
 'trace_tx_commit_to_cmac_ns':'TX commit → MAC (overlapping)',
 'e2e_raw_ns':'Raw roundtrip',
}
COLORS = {'hot off':'#147D92', 'cold off':'#D26932', 'hot on':'#67BDD0', 'cold on':'#EBAA7C'}
STAGE = {'blocked':'#9E79B4','packet':'#4289BD','queue':'#A6ABB2','kernel':'#32947B','user':'#DC9A35','tx':'#CC6570'}


def read(path):
    return json.loads(path.read_text())


def extract_events(raw, trace_map, center):
    # Select a short unwrapped window with vectorized raw timestamps, then use
    # the repository decoder for source-delay correction and payload fields.
    cfg = trace_map['sample']
    if cfg['sample_width'] != 128 or cfg['payload_width']+cfg['source_width'] < 64:
        raise ValueError('unsupported vectorized envelope')
    data = np.memmap(raw, dtype='<u8', mode='r').reshape(-1, 2)
    stamps = data[:, 1] >> (cfg['payload_width']+cfg['source_width']-64)
    indices = np.flatnonzero((stamps > center/5-40000) & (stamps < center/5+20000))
    result = []
    for index in indices:
        sample = int(data[index, 0]) | int(data[index, 1]) << 64
        source = h.dma.sample_source(sample, trace_map)
        if source == cfg['lost_source'] and h.dma.sample_lost_count(sample, trace_map):
            raise ValueError('loss in selected waterfall window')
        if h.dma.source_info(trace_map, source).get('type') != 'lauberhorn_event':
            continue
        event = h.event(sample, trace_map)
        if event:
            result.append(dict(time_ns=h.dma.sample_timestamp(sample, trace_map)*5,
                               event=event[0], data=event[1]))
    return sorted(result, key=lambda event:event['time_ns'])


def waterfall(ax, condition, row, events, server, timing=None, handoff=None):
    zero = row['trace_rx_cmac_entry_ns']; offset = row['trace_cpu_offset_ns']
    core = row['datapath_core']; end = row['trace_lh_cmac_to_cmac_ns']/1000
    lanes = []
    def bar(label, start, stop, kind, clipped=False):
        if stop < start:
            raise ValueError('reversed waterfall interval: '+label)
        lanes.append(dict(label=label, start_us=(start-zero)/1000,
                          end_us=(stop-zero)/1000, kind=kind, clipped=clipped))
    def cpu(value): return value-offset
    def event(name, low, high, on_core=False):
        found = [e for e in events if e['event']==name and low<=e['time_ns']<=high
                 and (not on_core or e['data'].get('CoreID')==core)]
        if len(found)!=1: raise ValueError(f'{name}: {len(found)} candidates')
        return found[0]['time_ns']
    reads = [e for e in events if e['event'] in ('EciRxReadFirst','EciRxReadNew')
             and e['data'].get('CoreID')==core and e['time_ns']<zero]
    if not reads: raise ValueError('no observed pre-arrival blocked RX')
    read_start = reads[-1]['time_ns']
    terminal = [e for e in events if e['event'] in ('EciRxDescSent','EciRxNackSent')
                and e['data'].get('CoreID')==core and read_start<e['time_ns']<zero]
    if terminal: raise ValueError('selected read was not still pending at MAC arrival')
    if condition=='hot':
        blocked_end=row['trace_core_delivery_ns']
        bar('Active worker: blocked RX',read_start,blocked_end,'blocked',True)
    else:
        nack=event('EciRxNackSent',zero,cpu(handoff['syscall_entry_ns']),True)
        bar('Outgoing worker: blocked RX',read_start,nack,'blocked',True)
    bar('Request: RX MAC → queue',zero,row['trace_request_queued_ns'],'packet')
    bar('Request waiting in FPGA',row['trace_request_queued_ns'],row['trace_request_dispatched_ns'],'queue')
    if condition=='cold':
        bar('DESCHED reply → yield entry',nack,cpu(handoff['syscall_entry_ns']),'user')
        bar('Outgoing yield entry → MMIO',cpu(handoff['syscall_entry_ns']),cpu(timing['read_begin']),'kernel')
        for label,a,b in [('Desched-info MMIO','read_begin','read_end'),('Claim incoming thread','read_end','claim'),
                          ('Set incoming affinity','claim','affinity'),('Publish activation','affinity','wake'),
                          ('Wake → incoming wait returns','wake','resume'),('Yield copyout','resume','return')]:
            bar(label,cpu(timing[a]),cpu(timing[b]),'kernel')
        bar('Kernel → incoming userspace',cpu(timing['return']),cpu(int(server['server_activation_ioctl_exit_ns'])),'kernel')
        bar('Runtime activation setup',cpu(int(server['server_activation_ioctl_exit_ns'])),cpu(int(server['server_activation_ready_ns'])),'user')
        bar('Runtime before first RX',cpu(int(server['server_activation_ready_ns'])),cpu(row['server_rx_enter_ns']),'user')
        incoming=event('EciRxReadFirst',zero,row['trace_core_delivery_ns'],True)
        bar('Incoming worker: RX read',incoming,row['trace_core_delivery_ns'],'blocked')
    rx_response=row.get('trace_rx_dcs_response_ns',row['trace_core_delivery_ns'])
    if 'trace_rx_dcs_response_ns' in row:
        bar('RX descriptor → DCS response',row['trace_core_delivery_ns'],rx_response,'packet')
    bar('RX response → userspace RX return',rx_response,cpu(row['server_rx_exit_ns']),'user')
    bar('Runtime before unmarshal',cpu(row['server_rx_exit_ns']),cpu(int(server['server_unmarshal_enter_ns'])),'user')
    for label,prefix in [('Unmarshal','server_unmarshal'),('Handler','server_handler'),('Marshal','server_marshal')]:
        bar(label,cpu(int(server[prefix+'_enter_ns'])),cpu(int(server[prefix+'_exit_ns'])),'user')
    commit=event('EciTxCommitRead',row['trace_core_delivery_ns'],zero+end*1000,True)
    submit=commit+row['trace_tx_commit_to_submit_ns']
    bar('Userspace TX call (overlapping)',cpu(row['server_tx_enter_ns']),cpu(row['server_tx_exit_ns']),'user')
    if row.get('server_tx_doorbell_ns'):
        probe=cpu(row['server_tx_doorbell_ns'])
        bar('TX preparation (raw CPU probes)',cpu(row['server_tx_enter_ns']),probe,'user')
        if 'trace_tx_dcs_request_ns' in row:
            bar('Doorbell probe → DCS request (estimate)',probe,row['trace_tx_dcs_request_ns'],'tx')
            bar('DCS request → FPGA commit read',row['trace_tx_dcs_request_ns'],commit,'tx')
        else:
            bar('Doorbell probe → FPGA commit read',probe,commit,'tx')
    bar('FPGA TX commit → submit',commit,submit,'tx')
    bar('TX encode / CDC → TX MAC',submit,zero+end*1000,'tx')
    left=-0.8 if condition=='hot' else -2.0
    for y,item in enumerate(lanes):
        a,z=item['start_us'],item['end_us']; color=STAGE[item['kind']]
        ax.barh(y,z-max(a,left),left=max(a,left),height=.57,color=color,alpha=.8 if item['kind']=='queue' else 1,
                hatch='///' if item['kind']=='queue' else None,edgecolor='white',linewidth=.5)
        if a<left:
            ax.annotate('',xy=(left+.02,y),xytext=(left+.22,y),arrowprops=dict(arrowstyle='->',color='#382547',lw=2))
            ax.text(left+.26,y,'began before frame',va='center',fontsize=7,color='#4B355B')
        ax.text(1.015,y,('start clipped' if a<left else f'{z-a:.3f} µs'),transform=ax.get_yaxis_transform(),va='center',fontsize=8,color='#414956')
    ax.set_yticks(range(len(lanes)),[item['label'] for item in lanes],fontsize=8.5)
    ax.set_ylim(len(lanes)-.4,-1);ax.set_xlim(left,end+(.3 if condition=='hot' else 1))
    ax.axvline(0,color='#25374A',ls='--',lw=1);ax.axvline(end,color=STAGE['tx'],ls=':',lw=1)
    ax.text(0,1.015,'RX MAC arrival',transform=ax.get_xaxis_transform(),fontsize=9,ha='left')
    ax.text(end,1.015,'TX MAC',transform=ax.get_xaxis_transform(),fontsize=9,ha='right',color=STAGE['tx'])
    ax.set_xlabel('Time from RX MAC arrival (µs)',fontsize=10);ax.grid(axis='x',alpha=.18);ax.set_axisbelow(True)
    for spine in ['top','right','left']:ax.spines[spine].set_visible(False)
    ax.tick_params(axis='y',length=0)
    ax.set_title(f"{condition.upper()} · request {row['request_id']} · CPU {row['rx_cpu_id']}\nRTT {row['e2e_raw_ns']/1000:.2f} µs | MAC → MAC {end:.3f} µs",loc='left',pad=36,fontsize=12,fontweight='bold')
    return lanes


def violin_grid(series, keys, value, title, subtitle, columns=4):
    fig, axes=plt.subplots((len(keys)+columns-1)//columns,columns,figsize=(16,3.0*((len(keys)+columns-1)//columns)),squeeze=False)
    for ax,key in zip(axes.flat,keys):
        for pos,(name,rows) in enumerate(series.items(),1):
            values=np.array([value(row,key)/1000 for row in rows]);color=COLORS.get(name,STAGE['kernel'])
            if np.ptp(values)>1e-10:
                violin=ax.violinplot(values,positions=[pos],widths=.72,showextrema=False,points=160,bw_method=.18)
                for body in violin['bodies']:body.set_facecolor(color);body.set_edgecolor(color);body.set_alpha(.65)
            else:ax.plot([pos-.25,pos+.25],[values[0],values[0]],color=color,lw=3)
            q=np.percentile(values,[0,5,50,95,100]);ax.plot([pos,pos],[q[0],q[4]],color=color,alpha=.45,lw=.8)
            ax.plot([pos,pos],[q[1],q[3]],color=color,lw=3);ax.scatter([pos],[q[2]],s=16,color='#172735',zorder=3)
        ax.set_title(LABELS.get(key,key),fontsize=9,fontweight='bold');ax.set_xticks(range(1,len(series)+1),list(series),fontsize=8)
        ax.set_ylabel('µs',fontsize=8);ax.grid(axis='y',alpha=.18);ax.set_axisbelow(True)
        ax.tick_params(axis='y',labelsize=8);ax.set_xlim(.4,len(series)+.6)
        if ax.get_ylim()[0]<0:ax.set_ylim(bottom=0)
        for spine in ['top','right']:ax.spines[spine].set_visible(False)
    for ax in list(axes.flat)[len(keys):]:ax.set_visible(False)
    height=fig.get_figheight()
    fig.suptitle(title,fontsize=17,fontweight='bold',y=1-.12/height)
    fig.text(.5,1-.5/height,subtitle,ha='center',fontsize=10)
    fig.tight_layout(rect=(0,.025,1,1-.8/height),h_pad=2.3,w_pad=1.8)
    fig.text(.5,.009,'Each panel has its own linear scale. Dot = median; thick line = P5–P95; thin line = full range. Constant values are lines, not artificial violins.',ha='center',fontsize=9)
    return fig


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--captures',type=Path,required=True)
    parser.add_argument('--handoff',type=Path,required=True)
    parser.add_argument('--trace-map',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--labels', nargs=4, metavar=('HOT_OFF','COLD_OFF','HOT_ON','COLD_ON'),
                        default=['bufhotoff1','bufcoldoff1','bufhot2','bufcold1'],
                        help='Capture prefixes in hot-off, cold-off, hot-on, cold-on order')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    labels=dict(zip(('hot off','cold off','hot on','cold on'), args.labels))
    audits={name:read(args.captures/(label+'-audit.json')) for name,label in labels.items()}
    series={name:audit['rows'] for name,audit in audits.items()}
    for rows in series.values():
        assert len(rows)==1000 and all(r['client_ok'] and r['server_ok'] for r in rows)
        assert all(abs(sum(r['breakdown'].values())-r['e2e_ns'])<1e-6 for r in rows)
    handoff=read(args.handoff);assert handoff['count']==1000
    trace_map=h.dma.load_map(args.trace_map)
    timings=parse_trace((args.captures/(labels['cold on']+'-kernel/trace.txt')).read_text())
    selected={};fig,axes=plt.subplots(1,2,figsize=(21,12),gridspec_kw={'width_ratios':[1,1.5]})
    for condition,ax in zip(['hot','cold'],axes):
        rows=series[condition+' on'];median=np.median([r['e2e_raw_ns'] for r in rows])
        row=min(rows,key=lambda r:(abs(r['e2e_raw_ns']-median),r['request_id']))
        label=labels[condition+' on'];raw=args.captures/(label+'-dump/trace/trace-dram.bin')
        events=extract_events(raw,trace_map,row['trace_rx_cmac_entry_ns'])
        with (args.captures/(label+'-server.csv')).open() as file:
            server=next(r for r in csv.DictReader(file) if int(r['request_id'])==row['request_id'])
        hr=next((r for r in handoff['rows'] if r['request_id']==row['request_id']),None) if condition=='cold' else None
        activation=read(args.captures/(labels['cold on']+'-activation.json')) if condition=='cold' else None
        ar=next(r for r in activation['rows'] if r['request_id']==row['request_id']) if activation else None
        timing=next(r for r in timings if r['worker']==ar['worker'] and r['sequence']==ar['activation_sequence']) if ar else None
        lanes=waterfall(ax,condition,row,events,server,timing,hr)
        selected[condition]=dict(request_id=row['request_id'],raw_rtt_ns=row['e2e_raw_ns'],lanes=lanes,
                                 raw_external_residual_ns=row['external_residual_raw_ns'])
    fig.suptitle('A request arriving while its core is blocked in RX',fontsize=21,fontweight='bold',y=.98)
    fig.text(.5,.945,'Actual requests nearest median RTT · kernel tracing ON in both panels · separate time scales',ha='center',fontsize=12)
    fig.subplots_adjust(left=.12,right=.93,bottom=.15,top=.84,wspace=.85)
    fig.text(.12,.075,'Bars are overlapping observations, not an additive stack. Blocked RX ends at the internal descriptor/NACK event; CPU completion follows.\nCold: outgoing worker and incoming thread share the physical core. Syscall-entry timing is quantized to 1 µs; CPU/FPGA epochs are fitted.',fontsize=10)
    medians={name:np.median([r['e2e_raw_ns'] for r in rows])/1000 for name,rows in series.items()}
    fig.text(.12,.032,'External residual is not placed on this timeline: its before-MAC / after-MAC split is unknown.\n'
             f"Kernel-OFF median RTT: hot {medians['hot off']:.2f} µs, cold {medians['cold off']:.2f} µs. "
             f"Cold kernel-ON/OFF median difference: {medians['cold on']-medians['cold off']:+.2f} µs (sequential cohorts).",fontsize=10)
    figures={'median-waterfall':fig}
    keys=list(series['hot off'][0]['breakdown'])
    figures['component-violins']=violin_grid(series,keys,lambda r,k:r['breakdown'][k],
        'Hot and cold: every additive E2E component','1,000 measured RPCs per condition · off/on = kernel tracing disabled/enabled · component medians must not be summed')
    figures['handoff-violins']=violin_grid({'cold on':handoff['rows']},list(handoff['rows'][0]['components_ns']),lambda r,k:r['components_ns'][k],
        'Cold handoff, including the outgoing domain → kernel transition','1,000 cooperative handoffs · kernel tracing ON · nested intervals, not extra RTT terms · entry timestamps: 1 µs resolution',columns=3)
    keys=['e2e_raw_ns','trace_lh_cmac_to_cmac_ns','client_tx_submit_observed_ns','client_poll_wait_observed_ns','trace_tx_commit_to_submit_ns','trace_tx_commit_to_cmac_ns']
    figures['interval-violins']=violin_grid(series,keys,lambda r,k:r[k],
        'Roundtrip and overlapping interval distributions','1,000 RPCs per condition · off/on = kernel tracing · observations overlap the additive components',columns=3)
    with PdfPages(args.output/'hot-cold-latency.pdf') as pdf:
        for name,figure in figures.items():
            for extension in ('png','svg','pdf'):figure.savefig(args.output/(name+'.'+extension),dpi=170,facecolor='white')
            pdf.savefig(figure);plt.close(figure)
    provenance={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in
                [args.handoff,args.trace_map,*[args.captures/(label+'-audit.json') for label in labels.values()]]}
    (args.output/'figure-data.json').write_text(json.dumps(dict(selected=selected,provenance=provenance),indent=2)+'\n')
    print(json.dumps(dict(output=str(args.output),figures=list(figures),selected={k:v['request_id'] for k,v in selected.items()})))

if __name__=='__main__': main()
