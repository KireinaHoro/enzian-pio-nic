"""Caladan server lifecycle inside the shared verified image namespace.

The caller supplies the shared peer upload/control helpers. All processes and
kernel/network changes are bounded and restored before exporting the archive.
"""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import time
import urllib.request


def ensure_ksched_device(path=Path('/dev/ksched')):
    """ksched registers a cdev but relies on setup_machine.sh for its node."""
    device=os.makedev(280,0)
    try:
        info=path.lstat()
    except FileNotFoundError:
        os.mknod(path,stat.S_IFCHR | 0o600,device)
        return True
    if not stat.S_ISCHR(info.st_mode) or info.st_rdev!=device:
        raise ValueError('Existing ksched device has wrong type or identity')
    return False


def nic_errors_unchanged(before, after):
    before=before.get('stats64',before.get('stats',{}))
    after=after.get('stats64',after.get('stats',{}))
    return all('errors' in before.get(direction,{}) and
               after.get(direction,{}).get('errors')==before[direction]['errors']
               for direction in ('rx','tx'))


def run(config, peer):
    root=Path(config['output']);root.mkdir(parents=True,exist_ok=False)
    (root/'request.json').write_text(json.dumps(config,indent=2)+'\n')
    runner=root/'runner';runner.mkdir()
    def save(name,value): (runner/name).write_text(json.dumps(value,indent=2)+'\n')
    def command(*args): return subprocess.check_output(args,text=True)
    def wait_for(predicate, processes, seconds, label):
        end=time.monotonic()+seconds
        while not predicate():
            if any(p.poll() is not None for p in processes): raise RuntimeError(label+': process exited')
            if time.monotonic()>end: raise TimeoutError(label)
            time.sleep(.1)
    def interrupted(signum,frame): raise RuntimeError('Caladan role interrupted')
    signal.signal(signal.SIGTERM,interrupted);signal.signal(signal.SIGINT,interrupted)
    interface=config['interface'];url=config['transfer_url'].rstrip('/');label=config['label']
    mode=config['instrumentation'];pool=config['caladan_cpus']
    cores=config['cpus'];prefix=label+'-caladan'
    settings={'/proc/sys/vm/nr_hugepages':'4096','/proc/sys/kernel/shm_rmid_forced':'1'}
    original={p:Path(p).read_text().strip() for p in settings}
    nic=json.loads(command('ip','-j','addr','show','dev',interface))[0]
    baseline=command('dmesg').splitlines()
    nic_before=json.loads(command('ip','-j','-s','link','show','dev',interface))[0]
    save('nic-before.json',nic_before)
    processes=[];modules=[];created_device=False;changed=False;error=None;restored=False;isolation=None
    manifest=json.loads(Path(config['manifest']).read_text())
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    before=dict(binary_sha256=hashlib.sha256(Path(config['binary']).read_bytes()).hexdigest(),
                lcores=cores,isolated_cpus=Path('/sys/devices/system/cpu/isolated').read_text().strip(),
                timestamp_provenance=None,caladan_cpus=pool,settings=original,nic=nic)
    save('before.json',before)
    try:
        if manifest['tracing']!=(mode=='breakdown'): raise ValueError('Compiled Caladan probe mode mismatch')
        if (len(pool)!=len(cores)+2 or pool[2:]!=cores or len(set(pool))!=len(pool)
                or pool!=sorted(pool) or any(type(cpu) is not int or not 0<=cpu<64 for cpu in pool)):
            raise ValueError('Caladan requires two separate IOKernel CPUs and the requested worker pool')
        if 'UP' in nic['flags'] or nic['addr_info']: raise ValueError('Caladan requires an idle unaddressed CX-5')
        if any(line.startswith(('ksched ','fake_idle ')) for line in Path('/proc/modules').read_text().splitlines()):
            raise ValueError('Scheduler modules already owned')
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try: argv=path.read_bytes().split(b'\0')
            except (FileNotFoundError,ProcessLookupError): continue
            if argv and Path(os.fsdecode(argv[0])).name in ('iokerneld','echo_caladan'):
                raise ValueError('Caladan processes already active')
        helper=config['isolation_helper']
        data=urllib.request.urlopen(helper['url'],timeout=30).read()
        if hashlib.sha256(data).hexdigest()!=helper['sha256']:raise ValueError('Isolation helper mismatch')
        namespace={'__name__':'cpu_isolation'};exec(compile(data,'cpu_isolation.py','exec'),namespace)
        isolation=namespace['Isolation'](cores,runner/'isolation.json')
        save('isolation-applied.json',isolation.apply())
        for name in ('fake_idle','ksched'):
            subprocess.run(['insmod','/nix/store/caladan/modules/'+name+'.ko'],check=True);modules.append(name)
        created_device=ensure_ksched_device()
        changed=True
        for path,value in settings.items(): Path(path).write_text(value)
        if int(Path('/proc/sys/vm/nr_hugepages').read_text())<4096: raise ValueError('Insufficient hugepages')
        subprocess.run(['ip','link','set',interface,'up'],check=True)
        net=ipaddress.ip_interface(config['source_cidr'])
        runtime=root/'runtime.config'
        runtime.write_text('\n'.join([
            'host_addr '+str(net.ip),'host_netmask '+str(net.netmask),
            'host_gateway '+config['caladan_gateway'],
            'runtime_kthreads '+str(len(cores)),
            'runtime_guaranteed_kthreads '+str(len(cores)),
            'runtime_spinning_kthreads '+str(len(cores)),
            'runtime_priority be','enable_directpath 1',
            'static_arp '+config['endpoint'][1]+' '+config['endpoint'][2]])+'\n')
        env=dict(os.environ)
        for key in list(env):
            if key.startswith(('LAUBERHORN_','CALADAN_ECHO_','LH_ECHO_','LH_BENCH_')): del env[key]
        env['CALADAN_ECHO_ACTIVITY']=str(root/'activity.csv')
        if mode=='breakdown':
            env.update(LAUBERHORN_SERVER_TRACE_CSV=str(root/'samples.csv'),
                       LAUBERHORN_TRACE_CAPACITY=str(config['count']+config['warmup']))
        jobs=[(['/nix/store/caladan/bin/iokerneld','simple','noht','nobw',
                ','.join(map(str,pool)),'nicpci','0005:90:00.0','--','--file-prefix='+prefix],
               'iokernel','running dataplane'),
              ([config['binary'],str(runtime),'12345'],'server','CALADAN_ECHO_READY')]
        for argv,name,marker in jobs:
            log=root/(name+'.log')
            with log.open('w') as stream:
                process=subprocess.Popen(argv,env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            processes.append(process)
            wait_for(lambda:marker in log.read_text(),processes,90,name+' readiness')
        if f'sched: dataplane on {pool[1]}, control on {pool[0]}' not in (root/'iokernel.log').read_text():
            raise ValueError('IOKernel placement differs from requested CPUs')
        log=(root/'server.log').read_text()
        steering=next((name for name in ('flow','queue') if 'selected '+name+' steering mode' in log),None)
        if steering is None:raise ValueError('Missing negotiated directpath steering mode')
        save('runtime.json',dict(steering=steering,control_cpu=pool[0],dataplane_cpu=pool[1],
                                worker_cpus=cores,runtime_kthreads=len(cores),
                                guaranteed_kthreads=len(cores),spinning_kthreads=len(cores),
                                control_cpus_boot_isolated=set(pool[:2])<=namespace['cpus'](before['isolated_cpus']),
                                activity_counters=True))
        peer['put'](url+'/'+label+'-ready',b'ready\n')
        wait_for(lambda:peer['present'](url+'/'+label+'-stop'),processes,config['timeout_seconds'],'client drain')
        # Traffic has stopped. Exports are outside every measured interval.
        app=processes[-1];app.send_signal(signal.SIGRTMIN+5)
        wait_for(lambda:(root/'activity.csv').exists(),processes,30,'activity export')
        if mode=='breakdown':
            app.send_signal(signal.SIGRTMIN+4)
            wait_for(lambda:(root/'samples.csv.status.json').exists(),processes,30,'trace export')
            status=json.loads((root/'samples.csv.status.json').read_text())
            if not status['loss_free'] or status['dropped']: raise ValueError('Lossy trace export')
    except BaseException as exc: error=str(exc)
    finally:
        cleanup=[]
        for process in reversed(processes):
            if process.poll() is None:
                process.send_signal(signal.SIGINT)
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill();process.wait();cleanup.append('forced process termination')
        for path in Path('/dev/hugepages').glob(prefix+'*'):
            if path.is_file():path.unlink()
        for name in reversed(modules):
            try:
                if name=='fake_idle':Path('/sys/fake_idle/unload').write_text('1')
                subprocess.run(['rmmod',name],check=True)
            except Exception as exc:cleanup.append(str(exc))
        if created_device:
            try:Path('/dev/ksched').unlink()
            except Exception as exc:cleanup.append(str(exc))
        if changed:
            for path,value in original.items():
                try:Path(path).write_text(value)
                except Exception as exc:cleanup.append(str(exc))
            if subprocess.run(['ip','link','set',interface,'down']).returncode:cleanup.append('NIC restoration')
        if isolation:
            try:
                save('interrupts.json',isolation.verify());isolation.restore()
            except Exception as exc:cleanup.append(str(exc))
        after={p:Path(p).read_text().strip() for p in settings}
        faults=[line for line in command('dmesg').splitlines() if line not in baseline and
                any(word in line for word in ('BUG:','Oops:','Call trace:','Internal error','soft lockup'))]
        nic_after=json.loads(command('ip','-j','-s','link','show','dev',interface))[0]
        save('nic-after.json',nic_after)
        if not nic_errors_unchanged(nic_before,nic_after):
            error=(error or '')+'; NIC error counters changed or unavailable'
        restored=not cleanup and after==original
        if faults: error=(error or '')+'; kernel fault'
        if not restored:error=(error or '')+'; restoration failed: '+str(cleanup)
        save('after.json',dict(settings=after,kernel_faults=faults,cleanup=cleanup))
        save('summary.json',dict(status='pass' if not error else 'fail',restored=restored))
        (root/'result.json').write_text(json.dumps(dict(returncode=0 if not error else 1,error=error))+'\n')
        peer['upload'](root,url)
    if error:raise RuntimeError(error)
