#!/usr/bin/env python3
"""Emit matched 518922 setup manifests; inspect before using the hardware harness."""
import argparse,json
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--workers',type=int,choices=range(1,5),required=True)
p.add_argument('--label',required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
if not a.label.replace('-','').replace('_','').isalnum():p.error('unsafe label')
case={
 'name':a.label,
 'revision':'cc19c4285148db65c5b5517f6a02abd04b7f9142',
 'image':'/var/tmp/trace-recorder-518922.img',
 'program':['ssh','enzian-ba2','/opt/Xilinx/Vivado/2023.2/bin/vivado','-mode','batch','-nojournal','-log',f'/tmp/lauberhorn-518922/program-{a.label}.log','-source','/tmp/lauberhorn-518922/program-arm.tcl','-tclargs','enzian-gateway.ethz.ch:3121','Digilent/210357B4B301A','/tmp/lauberhorn-518922/shell_lauberhorn-eci.bit','/tmp/lauberhorn-518922/shell_lauberhorn-eci.ltx'],
 'cpu':f'''ip link set cpu40g1 down
cpu configure 0c:53:31:03:01:c8 192.168.129.200/18
dmesg -n 4
ip neigh replace 192.168.128.225 lladdr 0c:53:31:03:00:e1 nud permanent dev lauberhorn0
mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
printf '0\\n' > /sys/kernel/tracing/tracing_on
export LAUBERHORN_TRACE_CAPACITY=250000
cpu serve add {a.workers} /dev/shm/{a.label}
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > /dev/shm/{a.label}/tad-before.txt
dmesg > /dev/shm/{a.label}/kernel-before.txt
''',
 'client':f'timeout 90 /tmp/trace-recorder-518922/rpc-client/bench_client 192.168.129.200 add 100 /tmp/paper-nullrpc-20261013/{a.label}-setup.csv'
}
if a.workers==1:
 case['cpu']+='''export LH_BENCH_FLUSH_SPIN_US=100
mkdir /dev/shm/paper-nullrpc-flush
nohup /nix/store/lauberhorn/apps/microbenchmarks/bin/microbenchmarks mul 4 > /dev/shm/paper-nullrpc-flush/server.log 2>&1 < /dev/null &
echo $! > /dev/shm/paper-nullrpc-flush/server.pid
sleep 2
kill -0 "$(cat /dev/shm/paper-nullrpc-flush/server.pid)"
'''
a.output.write_text(json.dumps([case],indent=2)+'\n')
