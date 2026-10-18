"""Unloaded-only adapter for the sibling project's maintained batch collector.

The collector and pairer own hardware access and association. This adapter owns
benchmark lifecycle and the real client ledger. Raw ticks never become wire_ns
without separate clock/reference qualification.
"""
import importlib.util
import json
from pathlib import Path
import re
import select
import shlex
import subprocess
import sys
import time

from .import_loaded import digest
from .import_unloaded import client_rows

FIELDS = ('client_ip', 'server_ip', 'client_udp_port', 'server_udp_port',
          'program', 'version', 'procedure')


def check_backend(backend):
    if not Path(backend['control_socket']).is_socket():
        raise ValueError('Authenticated switch control socket unavailable')
    if backend.get('exclusive_tx_fifo_ack') is not True:
        raise ValueError('Explicit exclusive switch FIFO ownership required')
    if type(backend.get('gap_ns')) is not int or not 100000 <= backend['gap_ns'] <= 1000000000:
        raise ValueError('Unloaded capture requires an explicit 0.1–1000 ms pacing gap')
    if not backend.get('expected_artifact_sha256') or not backend.get('expected_controller_sha256'):
        raise ValueError('Capture must pin deployed artifact and controller hashes')
    root=Path(backend['repository'])
    for relative in ('capture/batch_session.py','capture/pair_batch.py','capture/bfshell_launcher.py','tools/run-batch-probe.py'):
        if not (root/relative).is_file():
            raise ValueError('Missing maintained Tofino backend: '+relative)


def ledger(config, client_path, *, count, warmup, payload_bytes):
    if config['expected_count'] != count + warmup:
        raise ValueError('Capture and benchmark counts differ')
    rows = client_rows(client_path, count, warmup, payload_bytes)
    if count + warmup > 1024:
        raise ValueError('A capture chunk holds at most 1024 requests including warmups')
    gap=config.get('pacing_gap_ns',0)
    if any(int(b['client_call_enter_ns'])-int(a['client_call_enter_ns']) < gap for a,b in zip(rows,rows[1:])):
        raise ValueError('Client did not maintain the configured pacing gap')
    requests = []
    for sequence, row in enumerate(rows):
        if int(row.get('duplicates', 0)) or int(row.get('server_rejections', 0)):
            raise ValueError('Duplicate or rejected unloaded reply')
        requests.append(dict(sequence=sequence, xid=int(row['request_id']),
                             warmup=bool(int(row['warmup'])), completed=True, attempts=1,
                             rtt_ns=int(row['client_rx_burst_ns'])-int(row['client_call_enter_ns'])))
    if len({r['xid'] % 1024 for r in requests}) != len(requests):
        raise ValueError('XID low bits collide within capture chunk')
    return dict(session_id=config['session_id'], single_outstanding=True,
                **{k: config[k] for k in FIELDS}, requests=requests)


class Batch:
    """Bounded session; finish and preserve even when the benchmark fails."""
    def __init__(self, backend, config, output):
        check_backend(backend)
        self.backend, self.config, self.output = backend, config, Path(output)
        root = Path(backend['repository']).resolve()
        # Reuse the project's transport, launcher, schema validator and pairer.
        sys.path.insert(0, str(root/'capture'))
        spec = importlib.util.spec_from_file_location('tofino_batch_runner', root/'tools/run-batch-probe.py')
        self.api = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.api)
        self.api.validate_config(config)
        if not re.fullmatch('[A-Za-z0-9_-]{1,80}', config['session_id']):
            raise ValueError('Unsafe capture session ID')
        if not backend.get('expected_controller_sha256'):
            raise ValueError('Capture must pin deployed controller hashes')
        if not config.get('expected_artifact_sha256'):
            raise ValueError('Capture must pin deployed artifacts')
        if not Path(backend['control_socket']).is_socket():
            raise ValueError('Authenticated switch control socket unavailable')
        self.output.mkdir(parents=True, exist_ok=False)
        self.directory = '/tmp/tofino-batch-' + config['session_id']
        self.connection = self.api.ssh(backend['host'], backend['control_socket'])
        self.process = None
        self.transcript = b''
        self.closed = False
        self.write('config.json', config)
        self.write('implementation.json', {str(p.relative_to(root)): digest(p)
                   for p in [root/'tools/run-batch-probe.py', *sorted((root/'capture').glob('*.py'))]})

    def write(self, name, value):
        (self.output/name).write_text(json.dumps(value, indent=2)+'\n')

    def remote(self, args, data=None, timeout=30):
        return self.api.run(self.connection, ['docker', 'exec', '-i', 'syncd', *args], data, timeout)

    def start(self):
        launch = self.api.launcher(self.directory).encode()
        (self.output/'launch.py').write_bytes(launch)
        self.remote(['sh', '-c', 'mkdir -m 700 '+shlex.quote(self.directory)+
                     ' && cat > '+shlex.quote(self.directory+'/config.json')], json.dumps(self.config).encode())
        self.remote(['sh', '-c', 'cat > '+shlex.quote(self.directory+'/launch.py')], launch)
        command = ['docker', 'exec', '-it', 'syncd', 'timeout', '--signal=TERM', '--kill-after=30s',
                   str(int(self.config['timeout_s'])+360)+'s', '/opt/bfn/install/bin/bfshell',
                   '-b', self.directory+'/launch.py']
        self.process = subprocess.Popen(self.api.ssh(self.backend['host'], self.backend['control_socket'], True)
                      + [shlex.join(command)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        deadline = time.monotonic()+180
        token = ('BATCH_ARMED '+self.config['session_id']).encode()
        while token not in self.transcript:
            if time.monotonic() > deadline:
                raise TimeoutError('Tofino readiness deadline')
            if select.select([self.process.stdout], [], [], .1)[0]:
                chunk = self.process.stdout.read1(65536)
                if not chunk:
                    raise RuntimeError('Tofino collector exited before readiness')
                self.transcript += chunk
        ready = json.loads(self.remote(['cat', self.directory+'/ready.json']))
        if (ready['session_id'] != self.config['session_id'] or
                ready['artifact_sha256'] != self.config['expected_artifact_sha256'] or
                ready['controller_sha256'] != self.backend['expected_controller_sha256']):
            raise ValueError('Tofino readiness identity mismatch')
        self.write('ready.json', ready)

    def finish(self, generator_ok):
        if self.closed:
            return
        errors = []
        try:
            finish = dict(session_id=self.config['session_id'], generator_ok=bool(generator_ok))
            self.remote(['sh', '-c', 'cat > '+shlex.quote(self.directory+'/finish.tmp')+
                         ' && mv '+shlex.quote(self.directory+'/finish.tmp')+' '+
                         shlex.quote(self.directory+'/finish.json')], json.dumps(finish).encode())
            if self.process is not None:
                rest, _ = self.process.communicate(input=b'exit\n', timeout=180)
                self.transcript += rest
        except (OSError, subprocess.SubprocessError) as error:
            errors.append(str(error))
            # Do not signal syncd: retain owner/frozen state for explicit recovery.
        finally:
            (self.output/'bfshell.log').write_bytes(self.transcript)
            for name in ('config.json', 'journal.jsonl', 'result.json', 'finish.json'):
                try:
                    (self.output/name).write_bytes(self.remote(['cat', self.directory+'/'+name]))
                except (OSError, subprocess.SubprocessError) as error:
                    errors.append(name+': '+str(error))
            self.closed = True
            self.write('export.json', dict(passed=not errors, errors=errors, sender_stopped=True))
        if errors:
            raise RuntimeError('Tofino export/cleanup requires recovery: '+'; '.join(errors))
        result = json.loads((self.output/'result.json').read_text())
        if (result.get('config') != self.config or
                result.get('artifact_sha256') != self.config['expected_artifact_sha256'] or
                result.get('controller_sha256') != self.backend['expected_controller_sha256'] or
                result.get('journal_sha256') != digest(self.output/'journal.jsonl')):
            raise ValueError('Tofino result provenance or journal hash mismatch')
        if not all(result.get(k) is True for k in ('capture_complete', 'rx_export_complete', 'cleanup_ok')):
            raise ValueError('Incomplete Tofino capture or cleanup; preserve session before recovery')

    def pair(self, client_path, **options):
        request_ledger = ledger(self.config, client_path, **options)
        self.write('ledger.json', request_ledger)
        result = json.loads((self.output/'result.json').read_text())
        paired = self.api.pair(result, request_ledger)
        paired['input_sha256'] = {name: digest(self.output/(name+'.json')) for name in ('result', 'ledger')}
        self.write('pairs.json', paired)
        return paired
