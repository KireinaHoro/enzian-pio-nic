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

from .tofino import Batch, FIELDS, ledger, generate


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
        (batch.output/'pairs.json').write_text(json.dumps({'units':'unqualified_mac_ticks'}))

    def test_maintained_session_cli_owns_lifecycle(self):
        batch=self.batch();self.exported(batch)
        with patch(__package__+'.tofino.subprocess.run') as run:
            self.assertEqual(batch.run(['generator','argument']),{'units':'unqualified_mac_ticks'})
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
