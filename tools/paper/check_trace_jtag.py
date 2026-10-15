#!/usr/bin/env python3
"""Compare independent 4 KiB JTAG head/tail reads to a frozen full CMAC capture."""
import argparse
import json
from pathlib import Path
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--trace',type=Path,required=True)
p.add_argument('--pipeline',type=int,required=True)
p.add_argument('--label',required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
if not a.label.replace('-','').replace('_','').isalnum():p.error('unsafe label')
size=a.trace.stat().st_size
if size<4096 or size%64:p.error('invalid capture size')
a.output.mkdir(parents=True,exist_ok=False)
remote=f'/tmp/lauberhorn-{a.pipeline}'
script=a.output/'check.tcl'
script.write_text('''proc main {} {
 open_hw_manager
 connect_hw_server -url enzian-gateway:3121
 set targets [get_hw_targets */xilinx_tcf/Digilent/210357B4B301A]
 if {[llength $targets] != 1} {error "target mismatch"}
 current_hw_target [lindex $targets 0]
 open_hw_target
 set d [get_hw_devices xcvu9p_*]
 if {[llength $d] != 1} {error "device mismatch"}
 set_property PROBES.FILE REMOTE/shell_lauberhorn-eci.ltx $d
 refresh_hw_device $d
 set v [get_hw_vios -filter {CELL_NAME =~ */vio_trace_status}]
 refresh_hw_vio $v
 set stop [get_hw_probes -of_objects $v -filter {NAME =~ */trace_stop}]
 if {[get_property OUTPUT_VALUE $stop] ne "1"} {error "Trace must be frozen"}
 source REMOTE/dump_trace_hw_axi.tcl
 lhtrace::dump -out REMOTE/LABEL-head.bin -bytes 4096 -address 0 -use-vio-status 0
 lhtrace::dump -out REMOTE/LABEL-tail.bin -bytes 4096 -address TAIL -use-vio-status 0
 close_hw_target
}
if {[catch {main} message]} {puts stderr $message;exit 1}
exit 0
'''.replace('REMOTE',remote).replace('LABEL',a.label).replace('TAIL',str(size-4096)))
subprocess.run(['rsync',str(script),f'enzian-ba2:{remote}/{a.label}-check.tcl'],check=True)
with (a.output/'vivado.log').open('w') as log:
    subprocess.run(['ssh','enzian-ba2',f'/opt/Xilinx/Vivado/2023.2/bin/vivado -mode batch -nolog -nojournal -source {remote}/{a.label}-check.tcl'],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=240)
for part in ('head','tail'):
    subprocess.run(['rsync',f'enzian-ba2:{remote}/{a.label}-{part}.bin',str(a.output/(part+'.bin'))],check=True)
with a.trace.open('rb') as f:
    head=f.read(4096);f.seek(size-4096);tail=f.read(4096)
result=dict(head_matches=head==(a.output/'head.bin').read_bytes(),tail_matches=tail==(a.output/'tail.bin').read_bytes(),sample_bytes=4096)
(a.output/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
if not result['head_matches'] or not result['tail_matches']:raise SystemExit(1)
