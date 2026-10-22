#!/usr/bin/env python3
"""Run a benchmark on a reserved Enzian data NIC and restore its state.

mlx5 stays bound to mlx5_core/mlx5_ib (bifurcated PMD); NICVF uses VFIO.

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
from cpu_isolation import Isolation, cpus


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
    p.add_argument('--interface', default='cpu40g1')
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
    requested_cpus=set(lcores)
    if os.environ.get('LH_BENCH_AGGREGATE')=='1' and 'LH_BENCH_ACCOUNTING_CPU' in os.environ:
        try:
            accounting_cpu=int(os.environ['LH_BENCH_ACCOUNTING_CPU'])
        except ValueError:
            p.error('accounting CPU must be a nonnegative CPU number')
        if accounting_cpu<0 or accounting_cpu in requested_cpus:
            p.error('accounting CPU must be outside generator lcores')
        requested_cpus.add(accounting_cpu)
    # A login shell can inherit a mask excluding isolated CPUs. Expand this
    # process's mask explicitly; the kernel still enforces its cpuset cgroup.
    try:
        os.sched_setaffinity(0, os.sched_getaffinity(0) | requested_cpus)
    except (OSError, ValueError) as exc:
        p.error(f'cannot enable requested lcore: {exc}')
    if not requested_cpus <= os.sched_getaffinity(0):
        p.error('requested lcore is offline or excluded by the cpuset cgroup')
    if args.hugepages_mb <= 0 or args.timeout <= 0:
        p.error('positive hugepage budget and timeout required')
    app_args = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
    if len(app_args) not in (9, 10):
        p.error('provide the generator arguments after binary --')
    if not args.binary.is_file() or not os.access(args.binary, os.X_OK):
        p.error('generator must be an existing executable')
    interface = args.interface
    dev = Path('/sys/class/net') / interface / 'device'
    if not dev.exists():
        p.error('cpu40g1 must initially be bound to its kernel driver')
    dev = dev.resolve()
    bdf, original_driver = dev.name, (dev / 'driver').resolve().name
    if original_driver not in ('nicvf','mlx5_core'):
        p.error(f'unexpected ThunderX kernel driver {original_driver}')
    mlx5=original_driver=='mlx5_core'
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
    if not mlx5 and not any(a['local'] == app_args[0] for a in addresses['addr_info']):
        p.error('source address must belong to cpu40g1 before binding')
    provenance = None
    args.logs.mkdir(parents=True, exist_ok=False)
    snapshot = dict(timestamp_provenance=provenance, driver_override=original_override, neighbors=neighbors, pci=bdf, driver=original_driver, addresses=addresses, routes=routes,
                    kernel=command('uname','-r'), no_iommu=args.no_iommu, lcore=args.lcore, lcores=lcores,
                    binary_sha256=hashlib.sha256(args.binary.read_bytes()).hexdigest(),
                    kernel_cmdline=Path('/proc/cmdline').read_text().strip(),
                    isolated_cpus=Path('/sys/devices/system/cpu/isolated').read_text().strip(),
                    nohz_full_cpus=Path('/sys/devices/system/cpu/nohz_full').read_text().strip())
    (args.logs/'before.json').write_text(json.dumps(snapshot,indent=2)+'\n')
    command('modprobe','mlx5_ib' if mlx5 else 'vfio-pci')
    mode = Path('/sys/module/vfio/parameters/enable_unsafe_noiommu_mode')
    old_mode = None if mlx5 else mode.read_text().strip()
    if not mlx5 and not (dev/'iommu_group').exists() and not args.no_iommu:
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
    isolation=Isolation(set(lcores)|cpus(snapshot['isolated_cpus']),args.logs/'isolation-before.json')
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
        if mlx5:
            command('ip','link','set',interface,'up')
        else:
            if args.no_iommu: write(mode, 'Y')
            command('ip','link','set',interface,'down')
            changed_driver = True
            write(dev/'driver_override','vfio-pci')
            write(dev/'driver'/'unbind',bdf)
            write('/sys/bus/pci/drivers_probe',bdf)
            if (dev/'driver').resolve().name != 'vfio-pci':
                raise RuntimeError('VFIO binding did not succeed')
        summary['isolation']=isolation.apply()
        device_arg = bdf + (',rx_vec_en=0,mprq_en=0,rxq_cqe_comp_en=0,txq_mpw_en=0' if mlx5 else '')
        argv = [str(args.binary.resolve()), '-l', ','.join(map(str, lcores)), '-a', device_arg,
                '--file-prefix=lh-bench-'+str(os.getpid()), '--iova-mode='+('va' if mlx5 else 'pa'),
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
            summary['isolation_activity']=isolation.verify()
        except Exception as exc:
            summary['isolation_activity_error']=str(exc)
        try:
            isolation.restore()
        except Exception as exc:
            summary['isolation_restore_error']=str(exc)
            summary['status']='failed'
        try:
            if changed_driver and (dev/'driver').exists():
                write(dev/'driver'/'unbind',bdf)
            if not mlx5: write(mode,old_mode)
            if not mounted:
                command('umount','/dev/hugepages')
            write(pages_file,old_pages)
            if int(pages_file.read_text()) != old_pages or (not mlx5 and mode.read_text().strip() != old_mode):
                raise RuntimeError('hugepage/VFIO settings did not restore')
        except Exception as exc:
            summary['resource_restore_error'] = str(exc)
            summary['status'] = 'failed'
        try:
            if mlx5:
                # PMD initialization can change MTU while the bifurcated driver
                # remains bound. Restore it before verifying the saved netdev.
                command('ip','link','set',interface,'mtu',str(addresses['mtu']),
                        'address',addresses['address'])
                command('ip','link','set',interface,'up' if 'UP' in addresses['flags'] else 'down')
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
