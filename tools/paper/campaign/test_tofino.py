import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from .tofino import Batch, FIELDS, ledger, generate, wire_observations


class Tofino(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.config=dict(session_id='real-client-fixture',expected_count=3,expected_artifact_sha256={},
                         **dict.fromkeys(FIELDS,1))
        self.rows=[dict(request_id=i,benchmark='echo8',ok=1,rpc_status=0,warmup=int(i==1),
                       duplicates=0,server_rejections=0,
                       client_call_enter_ns=i*10000,client_rx_burst_ns=i*10000+1000)
                   for i in range(1,4)]

    def client(self):
        path=self.root/'client.csv'
        with path.open('w') as stream:
            writer=csv.DictWriter(stream,fieldnames=self.rows[0]);writer.writeheader();writer.writerows(self.rows)
        return path

    def test_real_client_ledger(self):
        value=ledger(self.config,self.client(),count=2,warmup=1,payload_bytes=8)
        self.assertEqual([r['xid'] for r in value['requests']],[1,2,3])
        self.assertEqual([r['warmup'] for r in value['requests']],[True,False,False])
        self.assertTrue(all(r['attempts']==1 and r['rtt_ns']==1000 for r in value['requests']))
        self.rows[1]['duplicates']=1
        with self.assertRaisesRegex(ValueError,'Duplicate'):
            ledger(self.config,self.client(),count=2,warmup=1,payload_bytes=8)

    def batch(self):
        batch=Batch.__new__(Batch)
        batch.backend=dict(repository='/maintained/tofino',control_socket='/tmp/socket',host='switch',
                           expected_controller_sha256={'controller':'pinned'})
        batch.config=self.config;batch.output=self.root/'capture'
        batch.config_path=self.root/'config.json'
        return batch

    def exported(self, batch, **changes):
        batch.output.mkdir()
        (batch.output/'journal.jsonl').write_bytes(b'journal')
        result=dict(config=self.config,artifact_sha256={},controller_sha256={'controller':'pinned'},
                    journal_sha256=hashlib.sha256(b'journal').hexdigest(),
                    capture_complete=True,rx_export_complete=True,cleanup_ok=True)
        result.update(changes)
        (batch.output/'result.json').write_text(json.dumps(result))
        (batch.output/'pairs.json').write_text(json.dumps({'units':'ns'}))

    def test_maintained_session_cli_owns_lifecycle(self):
        batch=self.batch();self.exported(batch)
        with patch(__package__+'.tofino.subprocess.run') as run:
            self.assertEqual(batch.run(['generator','argument']),{'units':'ns'})
        argv=run.call_args.args[0]
        self.assertEqual(argv[1:3],['/maintained/tofino/tools/capture-session.py','run'])
        self.assertEqual(argv[-3:],['--','generator','argument'])
        self.assertNotIn('ssh',argv)

    def test_failed_session_never_recovers_or_retries(self):
        batch=self.batch()
        with patch(__package__+'.tofino.subprocess.run',
                   side_effect=subprocess.CalledProcessError(1,'capture-session')) as run:
            with self.assertRaises(subprocess.CalledProcessError): batch.run(['generator'])
        self.assertEqual(run.call_count,1)
        self.assertTrue(batch.output.with_suffix('.session.log').exists())

    def test_invalid_provenance_and_cleanup_rejected(self):
        for changes in ({'cleanup_ok':False},{'controller_sha256':{}},{'journal_sha256':'wrong'}):
            with self.subTest(changes=changes):
                batch=self.batch()
                if batch.output.exists():
                    import shutil
                    shutil.rmtree(batch.output)
                self.exported(batch,**changes)
                with patch(__package__+'.tofino.subprocess.run'):
                    with self.assertRaises(ValueError): batch.run(['generator'])

    def test_generator_exports_actual_client_ledger(self):
        client=self.client()
        client.rename(self.root/'samples.csv')
        config=self.root/'config.json';config.write_text(json.dumps(self.config))
        spec=self.root/'spec.json'
        spec.write_text(json.dumps(dict(roles={'directory':'fixture'},
            ledger=dict(count=2,warmup=1,payload_bytes=8),result=str(self.root/'paths.json'))))
        env=dict(WIRE_CONFIG=str(config),WIRE_LEDGER=str(self.root/'ledger.json'),
                 WIRE_SESSION_ID=self.config['session_id'])
        with patch.dict(os.environ,env), patch(__package__+'.acquire.roles',return_value={'client':str(self.root)}):
            generate(spec)
            self.assertEqual(len(json.loads((self.root/'ledger.json').read_text())['requests']),3)
            with patch.dict(os.environ,{'WIRE_SESSION_ID':'wrong'}):
                with self.assertRaisesRegex(ValueError,'identity'): generate(spec)

    def test_nominal_ns_acceptance_is_separate_from_calibration(self):
        paired=dict(schema=3,session_id='fixture',pairing_complete=True,
                    latency_accepted=True,units='ns',ns_per_tick=1,
                    measurement='request_tx_to_reply_rx_mac',
                    hardware_timing_validated=False,absolute_wire_calibrated=False,
                    pairs=[dict(sequence=0,xid=1,warmup=False,wire_ns=100,delta_raw=100)])
        value=wire_observations(paired,'fixture',1)
        self.assertEqual(value['rows'][0]['wire_ns'],100)
        self.assertFalse(value['hardware_timing_validated'])
        from .wire import COMPONENTS, join_durations
        cpu=dict(flow_id=0,request_id=1,cpu_duration_ns=80,components_ns=dict.fromkeys(COMPONENTS,10))
        joined=join_durations([dict(flow_id=0,request_id=1,rtt_ns=150)],value['rows'],[cpu])
        self.assertEqual(joined[0]['components_ns']['cpu_wire'],20)
        for changes in ({'latency_accepted':False},{'session_id':'wrong'},{'ns_per_tick':.8},
                        {'pairs':[]},{'pairs':paired['pairs']*2}):
            with self.subTest(changes=changes),self.assertRaises(ValueError):
                wire_observations(dict(paired,**changes),'fixture',1)

    def test_unloaded_role_uses_continuously_paced_single_request_path(self):
        from . import peer
        manifest=self.root/'manifest.json';manifest.write_text('{}')
        config=dict(output=str(self.root/'role'),manifest=str(manifest),role='client',
                    mode='unloaded',system='LH',cpus=[16],deadline_ms=10,flows=1,
                    transfer_url='http://fixture',label='fixture',timeout_seconds=120,
                    binary='bench_dpdk',endpoint=['source','dest','mac',12345],
                    count=2,warmup=1,payload_bytes=8,gap_ns=10000000)
        process=Mock();process.wait.return_value=0;process.poll.return_value=0
        with patch.dict(os.environ,{'LH_BENCH_WINDOW':'64'}), \
             patch.object(peer.subprocess,'Popen',return_value=process) as launch, \
             patch.object(peer,'upload'):
            peer.run(config)
        env=launch.call_args.kwargs['env']
        self.assertNotIn('LH_BENCH_WINDOW',env)
        self.assertEqual(env['LH_BENCH_GAP_NS'],'10000000')

    def test_warmup_boundary_must_keep_capture_pacing(self):
        self.config['pacing_gap_ns']=10000
        self.rows[1]['client_call_enter_ns']=11500
        self.rows[1]['client_rx_burst_ns']=12500
        with self.assertRaisesRegex(ValueError,'pacing gap'):
            ledger(self.config,self.client(),count=2,warmup=1,payload_bytes=8)
