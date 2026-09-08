"""Shared reservation and serial-console operations for Enzian helpers."""
import contextlib
import getpass
import subprocess

import pexpect


def require_reservation(gateway, machine, owner=None):
    owner = owner or getpass.getuser()
    listing = subprocess.check_output(
        ['ssh', '-o', 'BatchMode=yes', gateway, 'emg list-machines'], text=True)
    if not any(len(row := line.split()) >= 3 and row[1:3] == [machine, owner]
               for line in listing.splitlines()):
        raise RuntimeError('Machine is not reserved by ' + owner)


@contextlib.contextmanager
def attach(gateway, machine, suffix, log):
    child = pexpect.spawn('ssh', ['-tt', '-o', 'BatchMode=yes', gateway,
                                  'console', machine + '-' + suffix],
                           encoding='utf-8', codec_errors='replace', timeout=30)
    child.logfile_read = log
    try:
        child.expect(r'Enter .* for help')
        if child.expect([r'\[no, .*attached\]', pexpect.TIMEOUT], timeout=1) == 0:
            raise RuntimeError('Console already attached: ' + suffix)
        yield child
    finally:
        if child.isalive():
            child.send('\x05c.')
            child.close()


def login_root(child, user, password):
    """Accept a login prompt or existing shell; do not match echoed sudo text."""
    child.sendline('')
    state = child.expect([r'login:', r'[^\r\n]+\$ ', r'[^\r\n]+# '])
    if state == 0:
        child.sendline(user)
        child.expect('Password:')
        child.sendline(password)
        child.expect(r'[^\r\n]+\$ ')
    if state != 2:
        child.sendline("sudo -S -p 'ENZIAN_SUDO:' bash")
        if child.expect([r'(?m)^ENZIAN_SUDO:', r'[^\r\n]+# ']) == 0:
            child.sendline(password)
            child.expect(r'[^\r\n]+# ')
