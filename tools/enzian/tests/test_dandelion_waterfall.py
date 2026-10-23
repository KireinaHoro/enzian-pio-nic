"""Reject ambiguous request graphs before plotting parallel execution."""
import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('waterfall',Path(__file__).parents[1]/'dandelion_waterfall.py')
w=importlib.util.module_from_spec(spec); spec.loader.exec_module(w)

class CorrelationTests(unittest.TestCase):
    def fixture(self,d,missing=False,duplicate=False):
        server=d/'server.csv'; events=d/'events.csv'
        with server.open('w') as f:
            wr=csv.writer(f); wr.writerow(['request_id','request_id_valid','ok','reply_status','transport_status'])
            for key in ([1] if missing else [1,2]): wr.writerow([key,1,1,0,w.CORE_TX_ACCEPTED])
        with events.open('w') as f:
            wr=csv.writer(f);wr.writerow(['request_id','timestamp_ns','event','arg0'])
            wr.writerow([1,10,106,2]);wr.writerow([1,30,108,2])
            if duplicate: wr.writerow([1,11,106,2])
        for p in [server,events]: Path(str(p)+'.status.json').write_text(json.dumps({'loss_free':True}))
        return server,events
    def test_unique_child_link(self):
        with tempfile.TemporaryDirectory() as tmp:
            r,e,c=w.load(*self.fixture(Path(tmp)))
            self.assertEqual(w.tree(1,c),[1,2])
    def test_missing_or_duplicate_child_is_not_guessed(self):
        for kwargs in [{'missing':True},{'duplicate':True}]:
            with tempfile.TemporaryDirectory() as tmp:
                with self.assertRaises(ValueError): w.load(*self.fixture(Path(tmp),**kwargs))
    def test_explicit_busy_attempt_is_not_an_executed_child(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths=self.fixture(Path(tmp))
            with paths[1].open('a') as f:
                f.write('1,12,106,3\n1,20,111,3\n')
            r,e,c=w.load(*paths)
            self.assertEqual(w.tree(1,c),[1,2])

    def test_retry_cannot_hide_execution_or_missing_submission(self):
        for line in ['1,40,111,2\n', '1,40,111,3\n']:
            with tempfile.TemporaryDirectory() as tmp:
                paths=self.fixture(Path(tmp))
                with paths[1].open('a') as f: f.write(line)
                with self.assertRaises(ValueError): w.load(*paths)

    def test_cycle_rejected(self):
        with self.assertRaises(ValueError): w.tree(1,{1:[2],2:[1]})
    def test_classification_does_not_include_process_execution(self):
        r={'server_entry_enter_ns':10,'server_entry_exit_ns':100,'execution_kind':0}
        spans=w.spans(r,[{'event':101,'timestamp_ns':20},
                         {'event':102,'timestamp_ns':25},
                         {'event':103,'timestamp_ns':30},
                         {'event':104,'timestamp_ns':90}])
        self.assertIn(('entry / classify',10,20),spans)
        self.assertIn(('process execution',30,90),spans)

    def test_deferred_return_admission_and_queue_are_disjoint(self):
        r={'server_entry_enter_ns':10,'server_entry_exit_ns':35,
           'server_defer_enqueue_ns':40,'execution_kind':1}
        spans=w.spans(r,[{'event':101,'timestamp_ns':20},
                         {'event':2,'timestamp_ns':50}])
        self.assertIn(('return to runtime',20,35),spans)
        self.assertIn(('fiber admission',35,40),spans)
        self.assertIn(('defer queue',40,50),spans)

    def test_publication_schema_uses_events_and_codec_boundaries(self):
        row=dict(accounting_version=4,execution_kind=1,server_decode_enter_ns=2,
                 server_decode_exit_ns=5,server_defer_enqueue_ns=40,
                 server_tx_enter_ns=80,server_tx_publish_ns=90)
        spans=w.spans(row,[{'event':100,'timestamp_ns':10},
                          {'event':101,'timestamp_ns':20},
                          {'event':1,'timestamp_ns':35},
                          {'event':2,'timestamp_ns':50}])
        self.assertIn(('decode',2,5),spans)
        self.assertIn(('entry / classify',10,20),spans)
        self.assertIn(('fiber admission',35,40),spans)
        self.assertIn(('final TX preparation',80,90),spans)
        self.assertEqual(w.tx_endpoint(row),90)

if __name__=='__main__': unittest.main()
