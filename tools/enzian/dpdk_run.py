#!/usr/bin/env python3
"""Run the generator on a reserved Enzian's cpu40g1 VF and restore its state.

Execute on the client as root, after checking its emg reservation externally.
Management/root storage must stay on cpu40g0. This wrapper never changes the PF.
Use --no-iommu only on a dedicated client whose firmware lacks an IOMMU group.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import time
from cpu_isolation import Isolation


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def write(path, value):
    Path(path).write_text(str(value)+'\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--logs', type=Path, required=True)
    p.add_argument('--lcore', type=int, required=True)
    p.add_argument('--worker-lcores', help='comma-separated additional polling CPUs')
    p.add_argument('--hugepages-mb', type=int, default=512)
    p.add_argument('--timeout', type=int, default=120)
    p.add_argument('--no-iommu', action='store_true')
    p.add_argument('--ptp-bgx', type=int, choices=range(8))
    p.add_argument('--manifest', type=Path,
                   help='deployment manifest; required for hardware timestamp runs')
    p.add_argument('binary', type=Path)
    p.add_argument('arguments', nargs=argparse.REMAINDER)
    args = p.parse_args()
    if os.geteuid() or args.lcore < 0:
        p.error('root and a nonnegative lcore are required')
    try:
        lcores = [args.lcore] + ([int(v) for v in args.worker_lcores.split(',')] if args.worker_lcores else [])
    except ValueError:
        p.error('worker-lcores must be comma-separated CPU numbers')
    if min(lcores) < 0 or len(set(lcores)) != len(lcores):
        p.error('lcores must be distinct nonnegative CPU numbers')
    if args.ptp_bgx is not None and len(lcores) != 1:
        p.error('PTP timestamping supports one polling CPU only')
    # A login shell can inherit a mask excluding isolated CPUs. Expand this
    # process's mask explicitly; the kernel still enforces its cpuset cgroup.
    try:
        os.sched_setaffinity(0, os.sched_getaffinity(0) | set(lcores))
    except (OSError, ValueError) as exc:
        p.error(f'cannot enable requested lcore: {exc}')
    if not set(lcores) <= os.sched_getaffinity(0):
        p.error('requested lcore is offline or excluded by the cpuset cgroup')
    if args.hugepages_mb <= 0 or args.timeout <= 0:
        p.error('positive hugepage budget and timeout required')
    app_args = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
    if len(app_args) not in (9, 10):
        p.error('provide the generator arguments after binary --')
    if not args.binary.is_file() or not os.access(args.binary, os.X_OK):
        p.error('generator must be an existing executable')
    interface = 'cpu40g1'
    dev = Path('/sys/class/net') / interface / 'device'
    if not dev.exists():
        p.error('cpu40g1 must initially be bound to its kernel driver')
    dev = dev.resolve()
    bdf, original_driver = dev.name, (dev / 'driver').resolve().name
    if original_driver != 'nicvf':
        p.error(f'unexpected ThunderX kernel driver {original_driver}')
    original_override = (dev/'driver_override').read_text().strip()
    if original_override == '(null)':
        original_override = ''
    neighbors = json.loads(command('ip', '-j', 'neigh', 'show', 'dev', interface))
    routes = json.loads(command('ip', '-j', 'route', 'show', 'dev', interface))
    if any(r.get('dst') == 'default' for r in routes):
        p.error('refusing a data interface carrying a default route')
    if 'dev cpu40g0' not in command('ip', 'route', 'get', '192.168.255.254'):
        p.error('management gateway must use cpu40g0')
    addresses = json.loads(command('ip', '-j', 'addr', 'show', 'dev', interface))[0]
    if not any(a['local'] == app_args[0] for a in addresses['addr_info']):
        p.error('source address must belong to cpu40g1 before binding')
    provenance = None
    if args.ptp_bgx is not None:
        if args.manifest is None:
            p.error('--ptp-bgx requires the matching deployment --manifest')
        manifest = json.loads(args.manifest.read_text())
        release = manifest['kernelRelease']
        if command('uname', '-r') != release:
            p.error('running kernel differs from the deployment manifest')
        generator = Path(manifest['packages']['generator']) / 'bin'
        if args.binary.resolve().parent != generator:
            p.error('binary is not from the manifest generator package')
        modules = Path(manifest['packages']['thunderxPtpModules']) / 'lib/modules' / release / 'extra'
        provenance = dict(manifest=manifest, modules={})
        for name in ('cavium_ptp', 'nicvf', 'thunder_bgx'):
            expected = modules / (name + '.ko')
            installed = Path(command('modinfo', '-n', name))
            source = command('modinfo', '-F', 'srcversion', str(expected))
            loaded = Path('/sys/module') / name / 'srcversion'
            if not source or not loaded.exists() or loaded.read_text().strip() != source:
                p.error(f'{name}: loaded module differs from the packaged source')
            digest = hashlib.sha256(expected.read_bytes()).hexdigest()
            if hashlib.sha256(installed.read_bytes()).hexdigest() != digest:
                p.error(f'{name}: installed module differs from the package')
            if not command('modinfo', '-F', 'vermagic', str(expected)).startswith(release + ' '):
                p.error(f'{name}: module ABI differs from the manifest')
            provenance['modules'][name] = dict(sha256=digest, srcversion=source)
        clocks = list(Path('/sys/class/ptp').glob('ptp*/clock_name'))
        if not clocks:
            p.error('hardware timestamp run requires an active PTP clock')
        provenance['clocks'] = {str(path): path.read_text().strip() for path in clocks}
    args.logs.mkdir(parents=True, exist_ok=False)
    snapshot = dict(timestamp_provenance=provenance, driver_override=original_override, neighbors=neighbors, pci=bdf, driver=original_driver, addresses=addresses, routes=routes,
                    kernel=command('uname','-r'), no_iommu=args.no_iommu, lcore=args.lcore, lcores=lcores,
                    binary_sha256=hashlib.sha256(args.binary.read_bytes()).hexdigest(),
                    kernel_cmdline=Path('/proc/cmdline').read_text().strip(),
                    isolated_cpus=Path('/sys/devices/system/cpu/isolated').read_text().strip(),
                    nohz_full_cpus=Path('/sys/devices/system/cpu/nohz_full').read_text().strip())
    (args.logs/'before.json').write_text(json.dumps(snapshot,indent=2)+'\n')
    command('modprobe','vfio-pci')
    mode = Path('/sys/module/vfio/parameters/enable_unsafe_noiommu_mode')
    old_mode = mode.read_text().strip()
    if not (dev/'iommu_group').exists() and not args.no_iommu:
        p.error('no IOMMU group; explicit --no-iommu is required on a dedicated client')
    pages_file = Path('/proc/sys/vm/nr_hugepages')
    old_pages = int(pages_file.read_text())
    page_kb = next(int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines()
                   if line.startswith('Hugepagesize:'))
    requested_pages = max(old_pages, math.ceil(args.hugepages_mb*1024/page_kb))
    mounted = subprocess.run(['mountpoint','-q','/dev/hugepages']).returncode == 0
    changed_driver = False
    proc = None
    summary = dict(status='failed', returncode=None, restored=False)
    isolation=Isolation(lcores,args.logs/'isolation-before.json')
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        write(pages_file, requested_pages)
        if int(pages_file.read_text()) < requested_pages:
            raise RuntimeError('could not allocate requested hugepages')
        if not mounted:
            Path('/dev/hugepages').mkdir(exist_ok=True)
            command('mount','-t','hugetlbfs','nodev','/dev/hugepages')
        if args.no_iommu:
            write(mode, 'Y')
        command('ip','link','set',interface,'down')
        changed_driver = True
        write(dev/'driver_override','vfio-pci')
        write(dev/'driver'/'unbind',bdf)
        write('/sys/bus/pci/drivers_probe',bdf)
        if (dev/'driver').resolve().name != 'vfio-pci':
            raise RuntimeError('VFIO binding did not succeed')
        summary['isolation']=isolation.apply()
        device_arg = bdf + (f',ptp_bgx={args.ptp_bgx}' if args.ptp_bgx is not None else '')
        argv = [str(args.binary.resolve()), '-l', ','.join(map(str, lcores)), '-a', device_arg,
                '--file-prefix=lh-bench-'+str(os.getpid()), '--iova-mode=pa',
                '--no-telemetry', '--legacy-mem', '-m', str(args.hugepages_mb), '--', *app_args]
        summary['argv'] = argv
        with (args.logs/'client.log').open('w') as log:
            proc = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            summary['returncode'] = proc.wait(timeout=args.timeout)
        summary['status'] = 'pass' if summary['returncode'] == 0 else 'failed'
    except (Exception, KeyboardInterrupt) as exc:
        summary['error'] = str(exc) or type(exc).__name__
    finally:
        if proc is not None and proc.poll() is None:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait()
        try:
            isolation.verify()
        except Exception as exc:
            summary['isolation_activity_error']=str(exc)
            summary['status']='failed'
        try:
            isolation.restore()
        except Exception as exc:
            summary['isolation_restore_error']=str(exc)
            summary['status']='failed'
        try:
            if changed_driver and (dev/'driver').exists():
                write(dev/'driver'/'unbind',bdf)
            write(mode,old_mode)
            if not mounted:
                command('umount','/dev/hugepages')
            write(pages_file,old_pages)
            if int(pages_file.read_text()) != old_pages or mode.read_text().strip() != old_mode:
                raise RuntimeError('hugepage/VFIO settings did not restore')
        except Exception as exc:
            summary['resource_restore_error'] = str(exc)
            summary['status'] = 'failed'
        try:
            if changed_driver:
                write(dev/'driver_override',original_driver)
                write('/sys/bus/pci/drivers_probe',bdf)
                write(dev/'driver_override',original_override)
                # Wait for this VF's netdev rather than depending on the host's
                # udev userspace. Binding is synchronous; naming can follow it.
                deadline = time.monotonic() + 10
                while True:
                    names = list((dev/'net').iterdir()) if (dev/'net').exists() else []
                    if len(names) == 1 or time.monotonic() >= deadline:
                        break
                    time.sleep(.05)
                if len(names) != 1:
                    raise RuntimeError('restored VF has no unique network interface')
                if names[0].name != interface:
                    command('ip','link','set',names[0].name,'name',interface)
                command('ip','link','set',interface,'mtu',str(addresses['mtu']),
                        'address',addresses['address'])
                if 'UP' in addresses['flags']:
                    command('ip','link','set',interface,'up')
                for address in addresses['addr_info']:
                    if address['scope'] != 'link':
                        cmd = ['ip','addr','replace', f"{address['local']}/{address['prefixlen']}", 'dev',interface]
                        if address['family'] == 'inet':
                            cmd += ['broadcast', address.get('broadcast', '0.0.0.0')]
                        command(*cmd)
                for route in routes:
                    if route.get('protocol') != 'kernel':
                        cmd = ['ip','route','replace',route['dst'],'dev',interface]
                        if 'gateway' in route: cmd += ['via',route['gateway']]
                        if 'metric' in route: cmd += ['metric',str(route['metric'])]
                        command(*cmd)
            for neighbor in neighbors:
                if 'PERMANENT' in neighbor.get('state', []) and 'lladdr' in neighbor:
                    command('ip','neigh','replace',neighbor['dst'],'lladdr',neighbor['lladdr'],
                            'nud','permanent','dev',interface)
            after_addresses = json.loads(command('ip','-j','addr','show','dev',interface))[0]
            def address_set(info):
                return {(a['local'], a['prefixlen'], a.get('broadcast'))
                        for a in info['addr_info'] if a['scope'] != 'link'}
            if ((dev/'driver').resolve().name != original_driver or
                    address_set(after_addresses) != address_set(addresses) or
                    after_addresses['mtu'] != addresses['mtu'] or
                    after_addresses['address'] != addresses['address']):
                raise RuntimeError('data VF driver/address/MTU did not restore')
            summary['restored'] = not any(k in summary for k in ('resource_restore_error','isolation_restore_error'))
        except Exception as exc:
            summary['restore_error'] = str(exc)
            summary['status'] = 'failed'
        (args.logs/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary))
    return 0 if summary['status'] == 'pass' else 1


if __name__ == '__main__':
    raise SystemExit(main())
