#!/usr/bin/env python3
"""Power cycle a reserved Enzian, catch BDK, program, then resume boot.

Requires Python pexpect and SSH access to the gateway. Logs received console
output (not sent passwords). On failure leaves the CPU stopped when possible;
never resumes boot after a failed programmer command.
"""
import argparse
import ast
import contextlib
import getpass
import os
import json
import copy
import pathlib
import re
import subprocess
import sys
import threading
import time

import pexpect
from console import attach, require_reservation


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--gateway', default='enzian-gateway')
    p.add_argument('--machine', default='zuestoll14')
    p.add_argument('--owner', default=getpass.getuser())
    p.add_argument('--logs', type=pathlib.Path, required=True)
    p.add_argument('--boot-attempts', type=int, default=10,
                   help='maximum cold-boot attempts after BDK ECI initialization failure (default: 10)')
    p.add_argument('--hold-only', action='store_true',
                   help='stop after powering FPGA with CPU held at BDK')
    p.add_argument('--cold-start', action='store_true',
                   help='skip power_down when BMC inspection confirms all rails are off')
    p.add_argument('--resume-held', action='store_true',
                   help='verify existing BDK menu, program and boot without power cycling')
    p.add_argument('--cpu-only', action='store_true',
                   help='boot a traffic-generator peer without an FPGA image or ECI acceptance')
    p.add_argument('--negative-no-bitstream', action='store_true',
                   help='negative test: power cycle and boot with an unprogrammed FPGA')
    p.add_argument('program', nargs=argparse.REMAINDER,
                   help='command after --; must exit zero only after programming succeeds')
    args = p.parse_args()
    args.negative_no_bitstream = args.negative_no_bitstream or args.cpu_only
    command = args.program
    if command[:1] == ['--']:
        command = command[1:]
    if (not command and not args.hold_only and not args.negative_no_bitstream) or not re.fullmatch(r'zuestoll\d{2}', args.machine):
        p.error('valid machine and programming command required')
    if args.negative_no_bitstream and (command or args.resume_held or args.hold_only or args.cold_start):
        p.error('--negative-no-bitstream requires a full power cycle and no programmer')
    if args.resume_held and (args.hold_only or args.cold_start):
        p.error('--resume-held cannot be combined with --hold-only or --cold-start')
    if args.boot_attempts < 1:
        p.error('--boot-attempts must be positive')
    run_attempts(args, command)


class EciBringupError(RuntimeError):
    """BDK did not establish all CPU-FPGA lanes; a cold reset may recover."""


def clock_report(transcript):
    """Retain firmware clock declarations verbatim, without treating them as measurements."""
    return [line.strip() for line in transcript.splitlines()
            if re.search(r'(?:MHz|GHz)\b|\b(?:[RSD]CLK|[Cc]lock)\b', line)]


def validate_eci_bringup(transcript):
    qlm_lines = re.findall(r'CDR lock[^\r\n]*', transcript)
    lane_lines = re.findall(r'N0\.CCPI Lanes\(\[\] is good\):([^\r\n]*)', transcript)
    if not qlm_lines:
        raise EciBringupError('Missing BDK QLM CDR lock report')
    locks = dict(re.findall(r'QLM(\d+):(\d+)', qlm_lines[-1]))
    if any(locks.get(str(qlm)) != '1' for qlm in range(8, 14)):
        raise EciBringupError('BDK QLM8..13 not all locked: ' + qlm_lines[-1])
    if not lane_lines:
        raise EciBringupError('Missing BDK CCPI lane initialization report')
    if lane_lines[-1].strip() != ''.join(f'[{lane}]' for lane in range(24)):
        raise EciBringupError('BDK CCPI lanes 0..23 not all initialized: ' + lane_lines[-1])
    return {'qlm_cdr_lock': qlm_lines[-1], 'ccpi_lanes': lane_lines[-1]}


def expect_eci_bringup(cpu):
    """Gate the actual expect stream at the end of BDK's ECI initialization."""
    try:
        cpu.expect(r'Initialize BGX|Initialize USB|Initialize PCIe|EDK2 version:|EFI stub:|login:', timeout=180)
    except pexpect.TIMEOUT as exc:
        raise EciBringupError('Timed out waiting for BDK ECI initialization') from exc
    return validate_eci_bringup(cpu.before), cpu.after == 'login:'


class ProgrammingBringupError(RuntimeError):
    """Explicit startup/calibration failure requiring a verified cold reset."""


def run_attempts(args, command):
    args.logs.mkdir(parents=True, exist_ok=False)
    results = []
    programming_failures = 0
    for number in range(1, args.boot_attempts + 1):
        attempt = copy.copy(args)
        if number > 1:
            # A failed resume/cold-start must recover with a full verified reset.
            attempt.resume_held = False
            attempt.cold_start = False
        logs = args.logs if number == 1 else args.logs / f'retry-{number:02d}'
        logs.mkdir(exist_ok=True)
        result = {'attempt': number, 'logs': str(logs), 'status': 'running'}
        results.append(result)
        try:
            result['eci'] = boot_once(attempt, command, logs)
            result['status'] = 'pass'
            return
        except ProgrammingBringupError as exc:
            programming_failures += 1
            result.update(status='programming_bringup_failed', error=str(exc))
            print(f'PROGRAMMING_BRINGUP_FAILED attempt={number}: {exc}', flush=True)
            if programming_failures >= 3 or number == args.boot_attempts:
                raise
            print('RETRYING_FULL_COLD_RESET', flush=True)
        except EciBringupError as exc:
            result.update(status='eci_bringup_failed', error=str(exc))
            print(f'ECI_BRINGUP_FAILED attempt={number}: {exc}', flush=True)
            if number == args.boot_attempts:
                raise
            print('RETRYING_FULL_COLD_RESET', flush=True)
        except Exception as exc:
            result.update(status='failed', error=str(exc))
            raise
        finally:
            (args.logs / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')


def program_held(command, logs):
    """Program once; run_attempts owns all retries and verified power cycling."""
    results = []
    path = logs / 'program.log'
    with path.open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                timeout=600)
    transcript = path.read_text()
    low = bool(re.search(r'End of startup status:\s*LOW', transcript))
    high = bool(re.search(r'End of startup status:\s*HIGH', transcript))
    calibrated = 'TRACE_DDR_CALIBRATION_PASS cell=i_app/design_1_i/ddr4_4' in transcript
    passed = result.returncode == 0 and high and not low and calibrated
    results.append(dict(attempt=1, log=str(path), returncode=result.returncode,
                        startup_high=high, calibration_pass=calibrated,
                        status='pass' if passed else 'failed'))
    (logs / 'program-summary.json').write_text(json.dumps(results, indent=2) + '\n')
    if passed:
        print('PROGRAM_AND_TRACE_DDR_VERIFIED', flush=True)
        return
    error = ProgrammingBringupError if low or result.returncode == 43 else RuntimeError
    raise error(f'FPGA programming/calibration failed; CPU remains held; see {path}')


def boot_once(args, command, logs):
    require_reservation(args.gateway, args.machine, args.owner)
    stack = contextlib.ExitStack()
    def console(suffix):
        log = stack.enter_context((logs / (suffix + '.log')).open('w'))
        child = stack.enter_context(attach(args.gateway, args.machine, suffix, log))
        child.sendline('')
        return child
    try:
        cpu = console('console')
        firmware_clocks = []
        def program_and_boot():
            if args.negative_no_bitstream:
                (logs / 'program.log').write_text(
                    ('CPU-ONLY PEER' if args.cpu_only else 'NEGATIVE TEST') +
                    ': FPGA power-cycled; programming deliberately skipped\n')
            else:
                program_held(command, logs)
            cpu.send('n')
            evidence = None
            if not args.negative_no_bitstream:
                evidence, login_ready = expect_eci_bringup(cpu)
                evidence['firmware_clock_report'] = firmware_clocks + clock_report(cpu.before)
                print('ECI_BRINGUP_VERIFIED ' + json.dumps(evidence), flush=True)
            else:
                login_ready = False
            if not login_ready:
                cpu.expect(r'login:', timeout=600)
            print('LINUX_LOGIN_READY', flush=True)
            return evidence
        if args.resume_held:
            cpu.expect('Boot Options')
            cpu.expect('Choice:')
            return program_and_boot()
        bmc = console('bmc')
        state = bmc.expect([r'login:', r'>>> ', r'(?m)[^\r\n]*[#] ', r'\[no, .*attached\]'])
        if state == 3:
            raise RuntimeError('BMC console already attached; detach its owner first')
        if state == 0:
            bmc.sendline('root')
            bmc.expect('Password:')
            bmc.sendline(os.environ.get('ENZIAN_BMC_PASSWORD')
                         or getpass.getpass('BMC root password: '))
            bmc.expect(r'(?m)[^\r\n]*[#] ')
        if state != 1:
            bmc.sendline('enzian-shell bringup')
            bmc.expect('>>> ', timeout=60)
        def power(call):
            bmc.sendline(call + '()')
            bmc.expect('>>> ', timeout=120)
            if re.search(r'Traceback|ERROR|Exception|AssertionError', bmc.before):
                raise RuntimeError('BMC command failed: ' + call)
        # Start the reader BEFORE issuing cpu_power_up; it reacts without an LLM.
        caught = []
        def hold_cpu():
            try:
                cpu.expect(r"Press[^\r\n]*[Bb][^\r\n]*for boot menu", timeout=180)
                firmware_clocks.extend(clock_report(cpu.before))
                cpu.send('b')
                cpu.expect('Boot Options')
                cpu.expect('Choice:')
                caught.append(True)
            except Exception as exc:
                caught.append(exc)
        if not args.cold_start:
            power('power_down')
        time.sleep(5)
        # Restore monitor access with core rails still disabled.
        power('common_power_up')
        bmc.sendline("readings = read_voltage_all(); print({n: [r[2] for r in readings[n] if r is not None] for n in ('VDD_CORE', 'VCCINT_FPGA')})")
        bmc.expect('>>> ', timeout=30)
        matches = re.findall(r"\{'VDD_CORE': \[.*?\], 'VCCINT_FPGA': \[.*?\]\}", bmc.before)
        if not matches:
            raise RuntimeError('Missing power-off voltage evidence')
        rails = ast.literal_eval(matches[-1])
        if any(not values or any(abs(v) > 0.2 for v in values) for values in rails.values()):
            raise RuntimeError('CPU/FPGA rails not verified off: ' + str(rails))
        print('POWER_OFF_VERIFIED ' + str(rails), flush=True)
        watcher = threading.Thread(target=hold_cpu, daemon=True)
        watcher.start()
        power('cpu_power_up')
        watcher.join(timeout=190)
        if caught != [True]:
            raise RuntimeError('Failed to hold CPU at BDK: ' + str(caught))
        print('BDK_HELD', flush=True)
        power('fpga_power_up')
        if args.hold_only:
            print('FPGA_POWERED_CPU_HELD: send n only after successful programming', flush=True)
            return
        return program_and_boot()
    finally:
        stack.close()

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'Boot failed: {exc}', file=sys.stderr)
        sys.exit(1)
