"""Unloaded-only adapter for the sibling project's maintained batch collector.

The collector and pairer own hardware access and association. This adapter owns
benchmark lifecycle and the real client ledger. The maintained pairer supplies
accepted nominal-ns MAC intervals; absolute calibration remains metadata.
"""
import json
import os
from pathlib import Path
import subprocess
import sys

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
    for relative in ('capture/batch_session.py','capture/pair_batch.py','capture/bfshell_launcher.py','tools/capture-session.py'):
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


def wire_observations(paired, session_id, count):
    """Accept the maintained pairer's explicit latency contract, not raw exports."""
    from .wire import METHOD
    if (paired.get('schema') != 3 or paired.get('session_id') != session_id or
            paired.get('pairing_complete') is not True or
            paired.get('latency_accepted') is not True or paired.get('units') != 'ns' or
            paired.get('ns_per_tick') != 1 or
            paired.get('measurement') != 'request_tx_to_reply_rx_mac'):
        raise ValueError('Missing accepted nominal-ns Tofino latency contract')
    pairs=paired['pairs']
    if len(pairs)!=count or len({p['xid'] for p in pairs})!=count:
        raise ValueError('Incomplete or duplicate Tofino pairs')
    rows=[]
    for i,pair in enumerate(pairs):
        if (pair['sequence']!=i or type(pair['warmup']) is not bool or
                type(pair['wire_ns']) is not int or pair['wire_ns']<=0 or
                pair['wire_ns']!=pair['delta_raw']):
            raise ValueError('Invalid nominal-ns Tofino observation')
        rows.append(dict(flow_id=0,request_id=pair['xid'],wire_ns=pair['wire_ns']))
    return dict(methodology=METHOD,fixture=False,session_id=session_id,rows=rows,
                latency_accepted=True,units='ns',ns_per_tick=1,
                measurement=paired['measurement'],
                hardware_timing_validated=paired['hardware_timing_validated'],
                absolute_wire_calibrated=paired['absolute_wire_calibrated'])


class Batch:
    """Delegate the entire switch lifecycle to the maintained session CLI."""
    def __init__(self, backend, config, output):
        check_backend(backend)
        self.backend, self.config, self.output = backend, config, Path(output).resolve()
        self.config_path = self.output.with_suffix('.config.json')
        self.config_path.write_text(json.dumps(config, indent=2)+'\n')

    def run(self, generator):
        command = [sys.executable,
                   str(Path(self.backend['repository'])/'tools/capture-session.py'),
                   'run', '--config', str(self.config_path), '--output', str(self.output),
                   '--switch-control', self.backend['control_socket'],
                   '--switch-host', self.backend['host'], '--', *map(str, generator)]
        # The session CLI owns all deadlines, export and failure preservation.
        # Never signal syncd or automatically recover an unsuccessful session.
        with self.output.with_suffix('.session.log').open('w') as log:
            subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT,
                           cwd=Path(__file__).resolve().parents[3])
        result = json.loads((self.output/'result.json').read_text())
        if (result.get('config') != self.config or
                result.get('controller_sha256') != self.backend['expected_controller_sha256'] or
                result.get('artifact_sha256') != self.config['expected_artifact_sha256'] or
                result.get('journal_sha256') != digest(self.output/'journal.jsonl')):
            raise ValueError('Tofino result provenance or journal hash mismatch')
        if not all(result.get(k) is True for k in ('capture_complete', 'rx_export_complete', 'cleanup_ok')):
            raise ValueError('Incomplete Tofino capture or cleanup; preserve session before recovery')
        return json.loads((self.output/'pairs.json').read_text())


def generate(spec_path):
    """Session-CLI generator adapter; uses the ordinary remote role harness."""
    from .acquire import roles
    spec = json.loads(Path(spec_path).read_text())
    config = json.loads(Path(os.environ['WIRE_CONFIG']).read_text())
    if config['session_id'] != os.environ['WIRE_SESSION_ID']:
        raise ValueError('Generator session identity mismatch')
    paths = roles(**spec['roles'])
    value = ledger(config, Path(paths['client'])/'samples.csv', **spec['ledger'])
    Path(os.environ['WIRE_LEDGER']).write_text(json.dumps(value, indent=2)+'\n')
    Path(spec['result']).write_text(json.dumps(paths)+'\n')


if __name__ == '__main__':
    generate(sys.argv[1])
