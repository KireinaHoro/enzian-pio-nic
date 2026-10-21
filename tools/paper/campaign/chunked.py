"""Resident-worker unloaded acquisition across bounded switch capture sessions."""
import csv
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .acquire import ROOT, roles, validate_role
from .import_loaded import digest
from .import_unloaded import dpdk, lauberhorn, rows
from .tofino import Batch, wire_observations


def plan(count, warmup=128):
    if type(count) is not int or count<=0 or warmup!=128:
        raise ValueError('Positive measured count and 128 warmups required')
    capacity=1024-warmup
    return [min(capacity,count-i) for i in range(0,count,capacity)]


def write_rows(path, data):
    with path.open('x') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(data[0]))
        writer.writeheader();writer.writerows(data)


def merge(cohorts):
    first=cohorts[0]
    result={k:v for k,v in first.items() if k not in ('rows','files','count','warmup','raw_capture','rx_preparation_ns')}
    result.update(rows=[],files={},count=0,warmup=0,chunks=[])
    for index,cohort in enumerate(cohorts):
        if any(cohort[k]!=first[k] for k in ('system','payload_bytes','instrumentation','build_config_sha256')):
            raise ValueError('Incompatible capture chunks')
        if cohort.get('cpu_partition_version')!=first.get('cpu_partition_version'):
            raise ValueError('Incompatible CPU accounting versions across chunks')
        if cohort['warmup']!=128 or not 0<cohort['count']<=896 or len(cohort['rows'])!=cohort['count']+128:
            raise ValueError('Invalid capture chunk size')
        result['chunks'].append(dict(index=index,count=cohort['count'],warmup=128,
                                     raw_capture=cohort['raw_capture']))
        for i,row in enumerate(cohort['rows'],1):
            if row['request_id']!=i or row['warmup']!=int(i<=128):
                raise ValueError('Invalid chunk identity or warmup')
            result['rows'].append(dict(row,wire_xid=i,chunk=index,request_id=len(result['rows'])+1))
        result['files'].update(cohort['files'])
        result['count']+=cohort['count'];result['warmup']+=128
    result['correlation']='Capture session plus wire XID; request_id is chronological observation index'
    return result


def run(a):
    from .hardware import Acquisition, atomic
    c,r=a.config,a.request
    from .tofino import check_backend
    check_backend(c['tofino'])
    counts=plan(r['count'],r['warmup'])
    if a.state.get('safe') is False:raise RuntimeError('Hardware recovery required')
    if r['system']=='DPDK':a.ensure_dpdk()
    if r['system']=='CS-A64':a.ensure_caladan()
    service=a.ensure_lh() if r['system']=='LH' else None
    atomic(a.state_path,dict(a.state,safe=False,stage=str(a.directory)))
    before=a.h.snapshot(service,a.label+'-before') if service else None
    if service:a.freeze_loaded()
    common=dict(gateway=c['gateway'],transfer_root=c['transfer_root'],
                image=c['client_image_remote'],image_sha=c['client_image_sha256'])
    backend=c['tofino'];endpoint=c['dut_endpoint'] if not service else c['fpga_endpoint']
    server_request=a.role('server') if not service else None
    if server_request:
        server_request.update(count=sum(counts)+128*(len(counts)-1),warmup=128,
                              chunk_requests=1024,deadline_ms=120000,timeout_seconds=14400)
    acquisitions=[];files={}
    with ThreadPoolExecutor(max_workers=1) as pool:
        server=pool.submit(roles,a.directory/'resident-server',
                           {'server':(c['dpdk_dut'],server_request)},**common) if server_request else None
        try:
            if server:
                ready=c['transfer_root']+'/'+a.label+'-ready'
                code='import pathlib,time\np=pathlib.Path('+repr(ready)+')\nend=time.monotonic()+120\nwhile not p.exists():\n if time.monotonic()>end:raise TimeoutError("Resident responder readiness")\n time.sleep(.1)\n'
                a.h.gateway(['python3','-c',code],'resident-ready',timeout=150)
            for index,count in enumerate(counts):
                directory=a.directory/f'chunk-{index:03d}';directory.mkdir()
                request=dict(r,count=count);atomic(directory/'request.json',request)
                child=Acquisition(c,request,directory,a.campaign)
                child.role_overrides={'client':dict(resident_label=a.label)}
                selector=backend['rpc_selectors'][str(r['payload_bytes'])]
                capture=Batch(backend,dict(session_id=child.label,dev_port=backend['ports'][r['system']],
                    client_ip=c['peer_endpoint']['ip'],server_ip=endpoint['ip'],client_udp_port=49152,
                    server_udp_port=12345,**selector,expected_count=count+128,timeout_s=600,
                    pacing_gap_ns=backend['gap_ns'],exclusive_tx_fifo_ack=True,
                    expected_artifact_sha256=backend['expected_artifact_sha256']),directory/'tofino')
                paths,evidence=child.execute_roles(capture,external_server=True)
                for item in evidence.values():files.update(item['files'])
                wire=capture.output/'wire.json'
                atomic(wire,wire_observations(child.paired,child.label,count+128))
                acquisitions.append((child,paths,capture,wire))
        finally:
            if server and r['system']=='CS-A64':
                a.stop_caladan()
        if server:
            server_path=Path(server.result()['server'])
            evidence=validate_role(server_path,server_request,a.server_sha())
            files.update(evidence['files'])
            server_rows=rows(server_path/'samples.csv') if r['system']!='CS-A64' or r['instrumentation']=='breakdown' else None
            a.cleanup_role(('server',(c['dpdk_dut'],server_request)))
        else:server_rows=None
    after=a.h.snapshot(service,a.label+'-after',export=r['instrumentation']=='breakdown') if service else None
    if service:
        a.h.command([sys.executable,ROOT/'tools/enzian/assess_tad.py','--before',before/'tad.txt',
            '--after',after/'tad.txt','--kernel-before',before/'kernel.txt','--kernel-after',after/'kernel.txt',
            '--output',a.directory/'health.json'],'health')
        if not json.loads((a.directory/'health.json').read_text())['passed']:raise ValueError('DUT health failed')
        if r['instrumentation']=='breakdown':
            server_rows=rows(after/'server.csv')
            if len(server_rows)!=a.state['setup_count']+sum(counts)+128*len(counts):
                raise ValueError('Incomplete resident runtime trace')
            server_rows=server_rows[a.state['setup_count']:]
    if server_rows is not None and len(server_rows)!=sum(counts)+128*len(counts):
        raise ValueError('Incomplete resident server samples')
    cohorts=[];offset=0
    for child,paths,capture,wire in acquisitions:
        count=child.request['count'];options=dict(count=count,warmup=128,instrumentation=r['instrumentation'],
                                               payload_bytes=r['payload_bytes'],wire_path=wire)
        if server_rows is not None:
            server_csv=child.directory/'server-slice.csv'
            write_rows(server_csv,server_rows[offset:offset+count+128]);options['server_path']=server_csv
        if service:cohort=lauberhorn(paths['client']/'samples.csv',setup_count=0,**options)
        elif r['system']=='CS-A64':
            from .import_caladan import caladan
            cohort=caladan(paths['client']/'samples.csv',
                status_path=server_path/'samples.csv.status.json',
                export_rows=sum(counts)+128*len(counts),**dict({'server_path':None},**options))
        else:cohort=dpdk(paths['client']/'samples.csv',**options)
        offset+=count+128
        cohort['build_config_sha256']=c['build_config_sha256'][r['system']][r['instrumentation']]
        cohort['raw_capture']=dict(path=str(capture.output/'pairs.json'),sha256=digest(capture.output/'pairs.json'))
        for row,raw in zip(cohort['rows'],child.paired['pairs']):
            if row['request_id']!=raw['xid'] or bool(row['warmup'])!=raw['warmup']:
                raise ValueError('Chunk wire/software identity mismatch')
            row['switch_delta_raw']=raw['delta_raw']
        cohort['files'].update({str(p):digest(p) for p in capture.output.iterdir() if p.is_file()})
        atomic(child.directory/'cohort.json',cohort);cohorts.append(cohort)
    cohort=merge(cohorts);cohort['paper_inputs']=r['phase']=='full'
    # Retain all raw roles, resident runtime exports and per-chunk provenance.
    from .adapter import files as evidence_files
    files.update(evidence_files(a.directory))
    cohort['files'].update(files)
    result=dict(passed=True,health=True,restored=True,files=files,cohort=cohort,xid_pairing=True,
                wire_available=True,tracing_compiled=r['instrumentation']=='breakdown',
                software_profiling=r['instrumentation']=='breakdown',trace_readouts=0,
                fpga_recording=False,nic_timestamping=False)
    atomic(a.state_path,dict(a.state,safe=True,recorder=a.last_recorder if service else None,
                           last_result=str(a.directory/'result.json')))
    return result
