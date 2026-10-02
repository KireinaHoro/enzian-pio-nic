#!/usr/bin/env python3
"""Production DCS control-array routing; test RAM/trace sinks, no payload datapath."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from check_dcs_pressure import DCS, ROOT, SOURCES
from capacity import alias


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rom', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    out = a.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    config = json.loads((ROOT/'model/data/selected-8k.json').read_text())[0]['configuration']
    basis = config['domain_encoding']['kernel_basis_bytes']
    offsets = [offset for direction in config['directions'].values()
               for key in ('control_offsets_bytes', 'overflow_offsets_bytes') for offset in direction[key]]
    addresses = []
    # Every line, every physical worker, both slices, high bits, A -> B -> A.
    for worker in config['workers']:
        for domain in (0, 1, (1 << len(basis))-1, 0):
            base = worker['base_bytes']
            for bit, vector in enumerate(basis):
                if domain & (1 << bit):
                    base ^= vector
            addresses.extend(alias((base+offset) >> 7) << 7 for offset in offsets)
    addresses.extend(alias(offset >> 7) << 7 for offset in offsets)
    vectors = out/'addresses.hex'
    vectors.write_text(''.join(f'{addr:010x}\n' for addr in addresses))
    sources = SOURCES[:3] + [a.rom.resolve()/f.name for f in SOURCES[4:6]] + SOURCES[6:7]
    sources += [DCS/f'dcs/dcu/{n}/rtl/{n}.sv' for n in (
        'dcu_tsu', 'tag_state_ram', 'ram_tdp', 'dcu', 'dcu_top', 'decode_eci_req',
        'gen_out_header', 'rd_trmgr', 'wr_trmgr', 'eci_trmgr')]
    sources += [DCS/f'dcs/dcu/arb_4_ecih/rtl/arb_{n}_ecih.sv' for n in (2, 3, 4)]
    sources += [DCS/f'common/{n}/rtl/{n}.sv' for n in (
        'axis_pipeline_stage', 'axis_comb_router', 'axis_comb_rr_arb')]
    sources += [DCS/f'dcs/{n}/rtl/{n}.sv' for n in ('dcs_rr_arb', 'dc_to_vc_router', 'dcs_dcus')]
    sources += [Path('hw/test/rtl/dcs_pressure')/name for name in (
        'XpmTdpModel.sv', 'DcsSliceRoutingTb.sv')]
    # Upstream's unsupported-size diagnostic omits the argument to %d.
    # Normalize only that string for Verilator; production logic is unchanged.
    arb = DCS/'dcs/dcs_rr_arb/rtl/dcs_rr_arb.sv'
    normalized = out/'dcs_rr_arb.sv'
    original = (ROOT/arb).read_text()
    old = 'DS_NUM_DCU_IDX %d is not supported.'
    assert original.count(old) == 1
    normalized.write_text(original.replace(old, 'DS_NUM_DCU_IDX is not supported.'))
    compile_sources = [normalized if f == arb else ROOT/f for f in sources]
    cmd = ['verilator', '--binary', '--timing', '--assert', '-Wno-fatal', '-j', '4',
           '--top-module', 'DcsSliceRoutingTb', '--Mdir', str(out/'obj'), '-o', 'test',
           '+define+DCS_LAUBERHORN', *map(str, compile_sources)]
    with (out/'build.log').open('w') as log:
        build = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, timeout=600)
    if build.returncode:
        print('\n'.join((out/'build.log').read_text().splitlines()[-35:]))
        return 1
    result = subprocess.run([str(out/'obj/test'), f'+vectors={vectors}'],
                            capture_output=True, text=True, timeout=120)
    log = result.stdout + result.stderr
    (out/'run.log').write_text(log)
    passed = result.returncode == 0 and 'PASS ' in log
    summary = dict(passed=passed, scope=__doc__, address_trials=len(addresses),
                   worker_count=len(config['workers']), domain_codes=[0, 1, (1 << len(basis))-1, 0],
                   sources={str(f): hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in sources},
                   diagnostic_only_normalization='Remove unmatched %d in upstream unsupported-size error',
                   vectors_sha256=hashlib.sha256(vectors.read_bytes()).hexdigest(),
                   verilator=subprocess.check_output(['verilator', '--version'], text=True).strip(),
                   command=cmd, checks=[line for line in log.splitlines() if line.startswith('PASS ')])
    (out/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(log)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
