#!/usr/bin/env python3
"""Targeted actual DCS controller/table/arbiter checks, not whole-slice validation."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[2]
DCS = Path('vivado/eci/directory-controller-slice')
SOURCES = [Path('vivado/eci/eci-toolkit/hdl/eci_cmd_defs.sv'),
    DCS/'dcs/dcu/eci_dirc_defs/eci_dirc_defs.sv',
    DCS/'dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv',
    DCS/'dcs/dcs_data_path/dp_gate/rtl/dp_gate.sv',
    DCS/'gen_dc_protocol/dc_stmc_auto_lock/cc_rom_from_cc_tbl/eci_cc_defs.sv',
    DCS/'gen_dc_protocol/dc_stmc_auto_lock/cc_rom_from_cc_tbl/eci_cc_table.sv',
    DCS/'dcs/dcu/dcu_controller/rtl/dcu_controller.sv',
    *(DCS/f'dcs/dcu/arb_4_ecih/rtl/arb_{n}_ecih.sv' for n in (2,3,4)),
    Path('hw/test/rtl/dcs_pressure/DcsPressureTb.sv')]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--trace', action='store_true', help='test real DCU debug port with full resident and local-lock sets')
    p.add_argument('--directory', action='store_true', help='populate actual TSU/tag RAM via protocol events; use test XPM primitive model')
    p.add_argument('--no-auto-lock', action='store_true', help='test the alternative table/controller, excluding the UL decoder')
    a=p.parse_args()
    if a.directory and a.trace: p.error('select directory or trace harness')
    if (a.directory or a.trace) and a.no_auto_lock: p.error('directory reachability test uses active auto_lock only')
    out=a.output.resolve(); out.mkdir(parents=True,exist_ok=False)
    sources = [Path(str(f).replace('dc_stmc_auto_lock/', 'dc_stmc_auto_no_lock/')) if a.no_auto_lock else f for f in SOURCES]
    top = 'DcsTraceTb' if a.trace else 'DcsDirectoryTb' if a.directory else 'DcsPressureTb'
    if a.directory or a.trace:
        sources = SOURCES[:3] + SOURCES[4:7] + [
            DCS/'dcs/dcu/dcu_tsu/rtl/dcu_tsu.sv',
            DCS/'dcs/dcu/tag_state_ram/rtl/tag_state_ram.sv',
            DCS/'dcs/dcu/ram_tdp/rtl/ram_tdp.sv',
            Path('hw/test/rtl/dcs_pressure/XpmTdpModel.sv'),
            Path('hw/test/rtl/dcs_pressure/DcsDirectoryTb.sv')]
    if a.trace:
        sources = sources[:-1] + [DCS/f'dcs/dcu/{n}/rtl/{n}.sv' for n in
            ('dcu', 'decode_eci_req', 'gen_out_header', 'rd_trmgr', 'wr_trmgr', 'eci_trmgr')]
        sources.append(Path('hw/test/rtl/dcs_pressure/DcsTraceTb.sv'))
    cmd=['verilator','--binary','--timing','--assert','-Wno-fatal','--top-module',top,
         '--Mdir',str(out/'obj'),'-o','test', *map(lambda f:str(ROOT/f),sources)]
    if a.no_auto_lock: cmd.append('+define+NO_AUTO_LOCK')
    with (out/'build.log').open('w') as log:
        build=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=180)
    if build.returncode:
        print('\n'.join((out/'build.log').read_text().splitlines()[-30:])); return 1
    result=subprocess.run([str(out/'obj/test')],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=30)
    (out/'run.log').write_text(result.stdout)
    passed=result.returncode==0 and result.stdout.count('PASS ')==(5 if a.no_auto_lock else 6)
    summary={'passed':passed, 'trace_test': a.trace, 'directory_test': a.directory, 'primitive_model': 'test-only common-clock XPM RAM' if a.directory or a.trace else None,'interpretation': 'expected unsafe full-miss write reproduced; not a viable drop-in' if a.no_auto_lock else 'active auto-lock allocation dependency reproduced', 'protocol': 'auto_no_lock' if a.no_auto_lock else 'auto_lock', 'scope':__doc__, 'command':cmd,
        'verilator':subprocess.check_output(['verilator','--version'],text=True).strip(),
        'sources':{str(f):hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in sources},
        'log_sha256':hashlib.sha256(result.stdout.encode()).hexdigest(),
        'checks':[line for line in result.stdout.splitlines() if line.startswith('PASS ')]}
    if a.trace:
        summary['interpretation'] = 'trace-only: full-miss backpressure preserved for resident and all-locked sets'
        summary['scope'] = 'Actual DCU debug outputs and shared slice address-selection helper; XPM primitive model; not full trace mux/DMA or board validation'
        for path in [DCS/'dcs/dcs_dcus/rtl/dcs_dcus.sv', Path('data/eci/sys_trace/trace_metadata.py')]:
            summary['sources'][str(path)] = hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
        import sys
        sys.path.insert(0,str(ROOT/'data/eci/sys_trace'))
        from trace_metadata import DCS_ERROR_CODE_NAMES
        assert DCS_ERROR_CODE_NAMES[7] == 'ERR_RTG_ALLOC_FULL'
    (out/'summary.json').write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n')
    print('\n'.join(summary['checks']) if a.directory and passed else result.stdout, end='\n')
    return 0 if passed else 1
if __name__=='__main__':raise SystemExit(main())
