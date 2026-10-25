#!/usr/bin/env python3
"""Concrete Enzian adapter for benchmark_campaign.py.

Invocation: python -m campaign.adapter HARDWARE.json STAGE REQUEST.json RESULT.json
Hardware paths, immutable build products and reservations are explicit inputs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from .acquire import ROOT, cpu_set
from .harness import Harness
from .hardware import Acquisition, atomic
from .import_loaded import digest, load
from .recovery import invalidated
from .settings import measurement_settings, validate_unloaded_counts
from .import_unloaded import validate_matched
from .policy import SYSTEMS, selected_systems, SIZES, WORKERS, loss, qualifying


def read(path): return json.loads(Path(path).read_text())


def files(directory):
    return {str(p.resolve()):digest(p) for p in Path(directory).rglob('*')
            if p.is_file() and p.name not in ('log.txt','result.json')}


def transfer(config,h):
    c=config
    identity={k:c[k] for k in ('transfer_root','transfer_bind','transfer_port','max_upload_bytes')}
    source=ROOT/'tools/paper/transfer_results.py';identity['source_sha256']=digest(source)
    code=f'''import json,os
from pathlib import Path
root=Path({c['transfer_root']!r});root.mkdir(exist_ok=True)
p=root/'transfer-config.json';expected={identity!r}
if p.exists() and json.loads(p.read_text())!=expected:raise ValueError('Incompatible transfer directory')
if not p.exists():p.write_text(json.dumps(expected))
s=os.statvfs(root)
if s.f_bavail*s.f_frsize<{c['gateway_reserve_bytes']!r}:raise ValueError('Gateway staging budget exhausted')
'''
    h.gateway(['python3','-c',code],'transfer-preflight')
    h.stage(source,'transfer-server.py')
    code=f'''import os,subprocess,time
from pathlib import Path
root=Path({c['transfer_root']!r});pidfile=root/'transfer.pid'
args=['python3',str(root/'transfer-server.py'),'--root',str(root),'--bind',{c['transfer_bind']!r},
      '--port',{str(c['transfer_port'])!r},'--max-upload-bytes',{str(c['max_upload_bytes'])!r},'--lifetime-seconds','604800']
alive=False
if pidfile.exists():
 pid=int(pidfile.read_text());p=Path('/proc')/str(pid)/'cmdline'
 if p.exists():
  actual=[x.decode() for x in p.read_bytes().split(b'\\0') if x]
  if actual!=args:raise ValueError('Transfer PID belongs to another command')
  alive=True
if not alive:
 with (root/'transfer.log').open('a') as log:
  child=subprocess.Popen(args,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 pidfile.write_text(str(child.pid));time.sleep(.3)
 if child.poll() is not None:raise RuntimeError('Transfer server failed to start')
'''
    h.gateway(['python3','-c',code],'transfer-start')


def callback_identity(config):
    command=config['notify']
    executable=shutil.which(command[0])
    return dict(command=command,thread=config['thread'],
                executable_sha256=digest(executable) if executable else None,
                environment={key:os.environ.get(key) for key in ('HOME','CODEX_HOME','PATH','SSH_AUTH_SOCK')})


def verify_callback(config, directory):
    identity=callback_identity(config)
    cache=ROOT/'out/echo-callback-delivery'/hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()
    if cache.exists():
        proof=read(cache)
        evidence=Path(proof['evidence'])
        if (proof['identity']==identity and digest(evidence)==proof['sha256']
                and read(evidence)['returncode']==0):
            atomic(directory/'callback-reused.json',proof)
            return
        raise ValueError('Callback delivery evidence changed; review before reprobe')
    notification=subprocess.run(config['notify']+['--thread',config['thread'],'--message',
        'Echo campaign callback delivery probe succeeded. This is a preflight probe, not a terminal acquisition result; no progress polling is needed.'],
        capture_output=True,text=True,timeout=30)
    evidence=(directory/'callback-probe.json').resolve()
    atomic(evidence,dict(returncode=notification.returncode,stderr=notification.stderr[-2000:]))
    if notification.returncode: raise RuntimeError('Terminal callback delivery probe failed')
    cache.parent.mkdir(parents=True,exist_ok=True)
    atomic(cache,dict(identity=identity,evidence=str(evidence),sha256=digest(evidence)))


def initial_recorder(config, h, state):
    """A fresh deployment has no inherited recorder to compare with the DUT."""
    if state:
        return 'resume'
    if config.get('initial_state') == 'fresh':
        if config.get('previous_capture') or config.get('previous_capture_summary'):
            raise ValueError('Fresh deployment cannot inherit a previous recorder')
        return 'fresh'
    prior=read(config['previous_capture_summary'])
    status=h.directory/'prior-vio.log';outputs=h.vio('status','echo-preflight',status)
    observed={k:int(v,16) for k,v in re.findall(r'^TRACE_STATUS (\w+) ([0-9a-fA-F]+)$',status.read_text(),re.M)}
    if outputs.get('trace_stop')!=1 or observed!=prior['end']:
        raise ValueError('Live recorder differs from preserved previous state')
    if digest(config['previous_capture'])!=prior['sha256']:
        raise ValueError('Previous capture preservation hash mismatch')
    return 'preserved'


def preflight(config, h, campaign, *, callback_required=True):
    c=config
    software_only=selected_systems(read(campaign/'config.json'))==('CS-A64',)
    if not software_only and not os.environ.get('ENZIAN_BMC_PASSWORD'):
        raise ValueError('Unattended boot requires ENZIAN_BMC_PASSWORD in the service environment')
    if c['dut'] != 'zuestoll07' or c['dpdk_dut'] != c['dut'] or c['peer'] != 'zuestoll14':
        raise ValueError('Use zuestoll07 for LH/ConnectX-5 and zuestoll14 for the generator')
    reservations=subprocess.check_output(['ssh',c['gateway'],'emg list-machines'],text=True,timeout=30)
    (h.directory/'reservations.txt').write_text(reservations)
    for machine in dict.fromkeys((c['dut'],c['peer'],c['dpdk_dut'])):
        if not re.search(r'^\s*\d+\s+'+re.escape(machine)+r'\s+'+re.escape(c['owner'])+r'\s+true\b',reservations,re.M):
            raise ValueError('Current reservation missing: '+machine)
    for path,sha in c['local_artifacts'].items():
        if digest(path)!=sha: raise ValueError('Local artifact mismatch: '+path)
    for artifact in c['remote_artifacts']:
        result=subprocess.check_output(['ssh',artifact['host'],shlex_join(['sha256sum',artifact['path']])],text=True,timeout=120)
        if result.split()[0]!=artifact['sha256']: raise ValueError('Remote artifact mismatch: '+artifact['path'])
    if shutil.disk_usage(campaign).free<c['local_reserve_bytes']:
        raise ValueError('Insufficient local campaign archive budget')
    state=read(campaign/'hardware-state.json') if (campaign/'hardware-state.json').exists() else None
    if state and state.get('safe') is not True: raise ValueError('Hardware recovery required before resume')
    if state and state.get('system')=='LH':
        current=h.vio('status','echo-resume-preflight',h.directory/'resume-vio.log')
        if current!=state.get('recorder'):
            raise ValueError('Live recorder differs from last successful cohort')
    # The transfer service is provisioned once by the launch preparation, with
    # a dedicated root and recorded PID. Its source/configuration are hashed.
    transfer(c,h)
    token=h.directory/'transfer-probe';token.write_text('echo campaign transport probe\n')
    probe_url=h.stage(token,'probe-'+hashlib.sha256(str(h.directory).encode()).hexdigest()[:16])
    h.gateway(['python3','-c',"import urllib.request;assert urllib.request.urlopen("+repr(probe_url)+",timeout=10).read()==b'echo campaign transport probe\\n'"],'transfer-readback')
    peer=h.probe(c['peer'],'peer-preflight-'+h.directory.parent.name+'-'+h.directory.name)
    dut=h.probe(c['dut'],'dut-preflight-'+h.directory.parent.name+'-'+h.directory.name)
    if not software_only and dut['deployment_sha256']!=c['deploy_image_sha256']:
        raise ValueError('DUT deployment image differs from matched artifact')
    baseline=h.probe(c['dpdk_dut'],'dpdk-preflight-'+h.directory.parent.name+'-'+h.directory.name)
    if baseline['nic_driver']!='mlx5_core' or baseline['nic_pci']!='0005:90:00.0':
        raise ValueError('ConnectX-5 device identity differs')
    for role,host,cores in (('peer',peer,c['generator_cpus']),('DUT',dut,c['worker_cpus']),('DPDK',baseline,c['worker_cpus'])):
        if (not set(cores)<=cpu_set(host['isolated']) or
                not set(cores)<=cpu_set(host['nohz_full']) or 'dev cpu40g0' not in host['management_route']):
            raise ValueError(role+' isolation or management routing mismatch')
        if host['kernel']!=c['kernel']: raise ValueError(role+' kernel mismatch')
    if peer['mem_available']<c['peer_ram_reserve_bytes'] or peer['shm']['available']<c['peer_output_reserve_bytes']:
        raise ValueError('Insufficient peer RAM/output budget')
    initial='fresh' if software_only else initial_recorder(c,h,state)
    if callback_required: verify_callback(c,h.directory)
    return dict(passed=True,reservations=True,matching_artifacts=True,storage_budget=True,
        ram_budget=True,restoration=True,callback_delivery=True if callback_required else None,callback_required=callback_required,isolated_cpus=True,initial_state=initial,preserved_previous_state=initial!='fresh')


def shlex_join(argv):
    import shlex
    return shlex.join(list(map(str,argv)))


def build(config,h):
    for i,command in enumerate(config['build_commands']): h.command(command,'build-'+str(i),timeout=7200)
    for path,sha in config['local_artifacts'].items():
        if digest(path)!=sha: raise ValueError('Built artifact differs from frozen configuration')
    if h.stage(config['client_image_local'],'client.img')!=config['client_image_url']:
        raise ValueError('Image URL differs from transfer endpoint')
    for machine in dict.fromkeys((config['peer'],config['dut'],config['dpdk_dut'])):
        h.python(machine,h.image_code(),'stage-image-'+machine,timeout=600)
    return dict(passed=True)


def publish_plots(output):
    """Atomically refresh the paper PDFs after plot validation."""
    import shutil
    destination=ROOT.parent/'paper/plots'
    destination.mkdir(parents=True,exist_ok=True)
    for source in Path(output).glob('*.pdf'):
        temporary=destination/(source.name+'.tmp')
        shutil.copyfile(source,temporary)
        temporary.replace(destination/source.name)


def pdf_checks(directory, names, *, loaded_system_count=2):
    import paper_chart
    dimensions=paper_chart.dimensions();previews=[];checks=[]
    for name in names:
        path=Path(directory)/(name+'.pdf')
        info=subprocess.check_output(['pdfinfo',str(path)],text=True,timeout=30)
        size=re.search(r'^Page size:\s+([\d.]+) x ([\d.]+) pts',info,re.M)
        if not size or not re.search(r'^Pages:\s+1$',info,re.M): raise ValueError('Invalid PDF geometry')
        width,height=map(float,size.groups());wide=name=='echo-overload-loss'
        expected=dimensions['text' if wide else 'column']*72
        expected_height=180 if name in ('echo-loaded-hot-peak-throughput-workers','echo-unloaded-hot-latency-cdf','echo-unloaded-hot-latency-breakdown') else 324
        if name=='echo-unloaded-hot-latency-breakdown': expected_height=388.8
        if name=='echo-unloaded-hot-latency-cdf': expected_height=252
        if name=='echo-loaded-hot-peak-throughput-workers': expected_height=230.4
        if name=='echo-loaded-hot-latency-goodput': expected_height=252
        if wide: expected_height=paper_chart.loaded_panel_height(loaded_system_count)*72
        if abs(width-expected)>.02 or abs(height-expected_height)>.02: raise ValueError('PDF dimensions differ from paper canvas')
        font_output=subprocess.check_output(['pdffonts',str(path)],text=True,timeout=30)
        font_rows=font_output.splitlines()[2:]
        if not font_rows or any(row.split()[-5]!='yes' for row in font_rows if row.strip()):
            raise ValueError('PDF has unembedded fonts')
        subprocess.run(['pdftoppm','-singlefile','-scale-to','1800','-png',str(path),str(path.with_suffix(''))],check=True,timeout=60)
        previews.append(str(path.with_suffix('.png').resolve()))
        checks.append(dict(file=str(path),width_pt=width,height_pt=height,fonts=font_output))
    atomic(Path(directory)/'pdf-validation.json',checks)
    return previews


def stop_transfer(config,h):
    cleanup=f'''import os,signal
from pathlib import Path
root=Path({config['transfer_root']!r});pid=int((root/'transfer.pid').read_text())
path=Path('/proc')/str(pid)/'cmdline'
if path.exists():
 args=[s.decode() for s in path.read_bytes().split(b'\\0') if s]
 if str(root/'transfer-server.py') not in args or str(root) not in args:raise ValueError('Transfer PID identity changed')
 os.kill(pid,signal.SIGTERM)
'''
    h.gateway(['python3','-c',cleanup],'stop-transfer')


def rehearsal(config,h,campaign):
    from .policy import validate_loaded_activity, validate_activity_coverage
    activities={}
    cohorts=read(campaign/'rehearsal-unloaded.json');validate_matched(cohorts,rehearsal=True,systems=selected_systems(read(campaign/'config.json')))
    if read(campaign/'config.json').get('scope')=='unloaded-rehearsal':
        output=h.directory/'plots'
        h.command([sys.executable,ROOT/'tools/paper/plot_echo_unloaded.py','--input',campaign/'rehearsal-unloaded.json',
            '--output',output,'--rehearsal'],'plot-unloaded',timeout=600)
        previews=pdf_checks(output,['echo-unloaded-hot-latency-cdf','echo-unloaded-hot-latency-breakdown'])
        backend=config['tofino']
        h.command([sys.executable,Path(backend['repository'])/'tools/qualify-clock.py',
            '--switch-control',backend['control_socket'],'--output',h.directory/'clock-after'],
            'clock-after',timeout=180)
        stop_transfer(config,h)
        return dict(passed=True,previews=previews,unloaded_only=True,cohorts=len(cohorts),
                    unloaded_modes=True,cleanup=True,**{'import':True,'plots':True})
    points={}
    for receipt in sorted((campaign/'stages').glob('*/receipt.json')):
        if invalidated(campaign,receipt): continue
        saved=read(receipt);request=next(p for p in saved['hashes'] if p.endswith('/request.json') and Path(p).parent.name.startswith('attempt-'))
        request=read(request)
        if request['stage']!='loaded' or request.get('phase')!='rehearsal': continue
        result=saved['result'];trial,_=load(result['csv'],result['summary'],collect_latencies=False)
        if result['trace_readouts'] or result['software_profiling'] or result['fpga_recording']:
            raise ValueError('Rehearsal loaded instrumentation violation')
        if not result['restored'] or not result['health']: raise ValueError('Rehearsal health/restoration failure')
        key=request['system'],request['payload_bytes'],request['workers']
        validate_loaded_activity(result['worker_activity'],request['workers'],trial)
        points.setdefault(key,[]).append(dict(rate=request['rate'],trials=[trial]))
        activities.setdefault(key,[]).append(result['worker_activity'])
    validate_activity_coverage(activities)
    expected={(s,n,w) for s in selected_systems(read(campaign/'config.json')) for n in SIZES for w in (1,4)}
    if set(points)!=expected: raise ValueError('Rehearsal matrix incomplete')
    records=[]
    for (system,size,workers),values in points.items():
        values.sort(key=lambda p:p['rate'])
        first=next((i for i,p in enumerate(values) if loss(p['trials'][0])>.01),None)
        if first is None or first==0:
            raise ValueError('Rehearsal must include low load and one valid point above the loss limit')
        records.append(dict(system=system,size=size,workers=workers,points=values))
    path=h.directory/'rehearsal-loaded.json';atomic(path,records)
    output=h.directory/'plots'
    h.command([sys.executable,ROOT/'tools/paper/plot_echo.py','--input',path,'--output',output,'--rehearsal'],'plot-loaded',timeout=7200)
    h.command([sys.executable,ROOT/'tools/paper/plot_echo_unloaded.py','--input',campaign/'rehearsal-unloaded.json',
        '--output',output,'--rehearsal'],'plot-unloaded',timeout=600)
    previews=pdf_checks(output,['echo-loaded-hot-latency-goodput','echo-overload-loss','echo-unloaded-hot-latency-cdf','echo-unloaded-hot-latency-breakdown'],
        loaded_system_count=len(selected_systems(read(campaign/'config.json'))))
    if read(campaign/'config.json').get('scope','rehearsal')=='rehearsal':
        stop_transfer(config,h)
    return dict(passed=True,previews=previews,unloaded_modes=True,loaded_sizes=True,overload_retained=True,scheduling=True,
                no_loaded_trace_reads=True,cleanup=True,**{'import':True,'plots':True})


def stop_after_unloaded(campaign):
    control=Path(campaign)/'control.json'
    return control.exists() and read(control).get('stop_after')=='unloaded'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('hardware',type=Path);p.add_argument('stage');p.add_argument('request',type=Path);p.add_argument('result',type=Path)
    a=p.parse_args();config=read(a.hardware);request=read(a.request)
    directory=a.request.parent.resolve();campaign=directory.parents[2]
    h=Harness(config,directory)
    if a.stage!=request['stage']: raise ValueError('Stage/request mismatch')
    if a.stage=='loaded' and stop_after_unloaded(campaign):
        raise RuntimeError('Requested stop after unloaded; loaded grid awaits revision. No loaded acquisition started.')
    if a.stage=='preflight': result=preflight(config,h,campaign)
    elif a.stage=='build': result=build(config,h)
    elif a.stage in ('loaded','unloaded'): result=Acquisition(config,request,directory,campaign).run()
    elif a.stage=='rehearsal-validation': result=rehearsal(config,h,campaign)
    elif a.stage=='progress-plots':
        unloaded='unloaded' in request
        source=directory/('unloaded.json' if unloaded else 'loaded.json')
        atomic(source,request['unloaded' if unloaded else 'loaded'])
        if unloaded: validate_matched(request['unloaded'],systems=selected_systems(read(campaign/'config.json')))
        final_unloaded=request.get('final_unloaded',False) or (unloaded and stop_after_unloaded(campaign))
        evidence={}
        if final_unloaded:
            if not unloaded: raise ValueError('Unloaded finalization requires unloaded cohorts')
            validate_unloaded_counts(request['unloaded'],measurement_settings(read(campaign/'config.json'))['unloaded'])
            for cohort in request['unloaded']:
                evidence.update(cohort['files'])
            if any(digest(path)!=sha for path,sha in evidence.items()):
                raise ValueError('Unloaded acquisition evidence changed')
            backend=config['tofino']
            h.command([sys.executable,Path(backend['repository'])/'tools/qualify-clock.py',
                '--switch-control',backend['control_socket'],'--output',directory/'clock-after'],
                'clock-after',timeout=180)
        output=directory/'plots'
        script='plot_echo_unloaded.py' if unloaded else 'plot_echo.py'
        h.command([sys.executable,ROOT/'tools/paper'/script,'--input',source,'--output',output]
                  + ([] if unloaded else ['--partial']),'plot-progress',timeout=600)
        names=['echo-unloaded-hot-latency-cdf','echo-unloaded-hot-latency-breakdown'] if unloaded else ['echo-loaded-hot-latency-goodput','echo-overload-loss','echo-loaded-hot-peak-throughput-workers']
        from .policy import observed_systems
        previews=pdf_checks(output,names,loaded_system_count=
            len(observed_systems(request['loaded'])) if not unloaded else 2)
        if config.get('publish_plots',True): publish_plots(output)
        atomic(campaign/('unloaded-plots.json' if unloaded else 'loaded-plots.json'),dict(previews=previews))
        result=dict(passed=True,previews=previews)
        if final_unloaded:
            atomic(directory/'manifest.json',dict(status='complete',paper_inputs=True,
                unloaded=str(source),hardware=config,files=evidence,visual_review='pending terminal callback'))
            stop_transfer(config,h)
            result.update(complete=True,files=evidence)
    elif a.stage=='finalize':
        from .finalize import finalize
        result=finalize(config,h,request,campaign)
    else: raise ValueError('Unsupported campaign stage')
    result.setdefault('files',{}).update(files(directory))
    atomic(a.result,result)
    print(json.dumps(dict(passed=True,stage=a.stage,result=str(a.result))))


if __name__=='__main__':main()
