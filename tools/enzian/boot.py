#!/usr/bin/env python3
"""Power cycle a reserved Enzian, catch BDK, program, then resume boot.

Requires Python pexpect and SSH access to the gateway. Logs received console
output (not sent passwords). On failure leaves the CPU stopped when possible;
never resumes boot after a failed programmer command.
"""
import argparse
import getpass
import pathlib
import re
import subprocess
import sys
import threading

import pexpect


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--gateway', default='enzian-gateway')
    p.add_argument('--machine', default='zuestoll14')
    p.add_argument('--owner', default=getpass.getuser())
    p.add_argument('--logs', type=pathlib.Path, required=True)
    p.add_argument('--hold-only', action='store_true',
                   help='stop after powering FPGA with CPU held at BDK')
    p.add_argument('--cold-start', action='store_true',
                   help='skip power_down when BMC inspection confirms all rails are off')
    p.add_argument('--resume-held', action='store_true',
                   help='verify existing BDK menu, program and boot without power cycling')
    p.add_argument('program', nargs=argparse.REMAINDER,
                   help='command after --; must exit zero only after programming succeeds')
    args = p.parse_args()
    command = args.program
    if command[:1] == ['--']:
        command = command[1:]
    if (not command and not args.hold_only) or not re.fullmatch(r'zuestoll\d{2}', args.machine):
        p.error('valid machine and programming command required')
    if args.resume_held and (args.hold_only or args.cold_start):
        p.error('--resume-held cannot be combined with --hold-only or --cold-start')
    reservation = subprocess.check_output(
        ['ssh', '-o', 'BatchMode=yes', args.gateway, 'emg list-machines'], text=True)
    if not any(len(row := line.split()) >= 3 and row[1:3] == [args.machine, args.owner]
               for line in reservation.splitlines()):
        raise RuntimeError('Machine is not reserved by the requested owner')
    args.logs.mkdir(parents=True, exist_ok=False)
    sessions = []
    files = []
    def console(suffix):
        log = (args.logs / (suffix + '.log')).open('w')
        files.append(log)
        child = pexpect.spawn('ssh', ['-tt', '-o', 'BatchMode=yes', args.gateway,
                                      'console', args.machine + '-' + suffix],
                               encoding='utf-8', codec_errors='replace', timeout=30)
        child.logfile_read = log
        sessions.append(child)
        child.expect(r'Enter .* for help')
        # Conserver emits attachment refusal immediately after its greeting.
        if child.expect([r'\[no, .*attached\]', pexpect.TIMEOUT], timeout=1) == 0:
            raise RuntimeError('Console already attached: ' + suffix)
        child.sendline('')
        return child
    try:
        cpu = console('console')
        def program_and_boot():
            with (args.logs / 'program.log').open('w') as log:
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                               check=True, timeout=600)
            cpu.send('n')
            cpu.expect(r'login:', timeout=600)
            print('LINUX_LOGIN_READY', flush=True)
        if args.resume_held:
            cpu.expect('Boot Options')
            cpu.expect('Choice:')
            program_and_boot()
            return
        bmc = console('bmc')
        state = bmc.expect([r'login:', r'>>> ', r'(?m)[^\r\n]*[#] ', r'\[no, .*attached\]'])
        if state == 3:
            raise RuntimeError('BMC console already attached; detach its owner first')
        if state == 0:
            bmc.sendline('root')
            bmc.expect('Password:')
            bmc.sendline(getpass.getpass('BMC root password: '))
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
                cpu.send('b')
                cpu.expect('Boot Options')
                cpu.expect('Choice:')
                caught.append(True)
            except Exception as exc:
                caught.append(exc)
        if not args.cold_start:
            power('power_down')
        power('common_power_up')
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
        program_and_boot()
    finally:
        for child in sessions:
            if child.isalive():
                child.send('\x05c.')
                child.close()
        for log in files:
            log.close()

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'Boot failed: {exc}', file=sys.stderr)
        sys.exit(1)
