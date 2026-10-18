import csv
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from .tofino import Batch, FIELDS, ledger


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

    def test_failed_cleanup_is_preserved_and_rejected(self):
        batch=Batch.__new__(Batch)
        batch.backend=dict(expected_controller_sha256={})
        batch.config=self.config;batch.output=self.root;batch.directory='/tmp/fixture'
        batch.closed=False;batch.process=Mock();batch.process.communicate.return_value=(b'finished',None)
        batch.transcript=b'armed'
        result=dict(config=self.config,artifact_sha256={},controller_sha256={},
                    journal_sha256=hashlib.sha256(b'journal').hexdigest(),
                    capture_complete=True,rx_export_complete=True,cleanup_ok=False)
        batch.remote=Mock(side_effect=lambda args,*rest: b'journal' if args[-1].endswith('journal.jsonl') else json.dumps(result).encode())
        with self.assertRaisesRegex(ValueError,'cleanup'):
            batch.finish(True)
        self.assertTrue(batch.closed)
        self.assertEqual(json.loads((self.root/'result.json').read_text()),result)
        self.assertEqual((self.root/'bfshell.log').read_bytes(),b'armedfinished')

    def test_export_failure_never_signals_switch(self):
        batch=Batch.__new__(Batch)
        batch.backend=dict(expected_controller_sha256={})
        batch.config=self.config;batch.output=self.root;batch.directory='/tmp/fixture'
        batch.closed=False;batch.process=Mock();batch.transcript=b'armed'
        batch.remote=Mock(side_effect=subprocess.TimeoutExpired('fixture',30))
        with self.assertRaisesRegex(RuntimeError,'recovery'):
            batch.finish(False)
        batch.process.terminate.assert_not_called()
        batch.process.kill.assert_not_called()
        self.assertFalse(json.loads((self.root/'export.json').read_text())['passed'])
        self.assertTrue((self.root/'bfshell.log').exists())
