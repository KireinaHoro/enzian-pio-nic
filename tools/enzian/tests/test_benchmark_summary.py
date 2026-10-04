import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('benchmark_summary', Path(__file__).resolve().parents[1] / 'benchmark_summary.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class BenchmarkSummary(unittest.TestCase):
    def summarize(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'client.csv'
            with path.open('w') as f:
                writer = csv.DictWriter(f, fieldnames=rows[0])
                writer.writeheader(); writer.writerows(rows)
            return m.summarize(path)

    def row(self, request_id=1, **kw):
        return dict(request_id=request_id, ok=1, warmup=0,
                    client_call_enter_ns=100, client_call_exit_ns=200,
                    client_tx_ready_ns=110, client_tx_submit_ns=120,
                    client_rx_burst_ns=180, timestamp_overhead_ns=5, **kw)

    def test_partition_and_warmup(self):
        warmup=self.row(); warmup['warmup']=1
        result=self.summarize([warmup,self.row(2)])
        self.assertTrue(result['valid'])
        self.assertEqual(result['warmup'],1)
        metrics=result['metrics_ns']
        self.assertEqual(sum(metrics[k]['p50'] for k in ('encode','tx_submit','poll_to_receive','validate')),100)
        self.assertEqual(metrics['e2e_corrected']['p50'],95)
        self.assertEqual(metrics['client_software']['p50'],40)

    def test_failures_remain_visible(self):
        failed=self.row(); failed['ok']=0
        result=self.summarize([failed,self.row(2)])
        self.assertFalse(result['valid'])
        self.assertEqual(result['failures'],[1])

    def test_reversed_interval_rejected(self):
        row=self.row(); row['client_rx_burst_ns']=90
        with self.assertRaises(ValueError): self.summarize([row])

    def test_duplicate_id_rejected(self):
        with self.assertRaises(ValueError): self.summarize([self.row(),self.row()])

    def test_audit_must_match_exact_client(self):
        result = dict(sha256='same', measured_request_ids=[1, 2])
        audit = dict(provenance=dict(client_sha256='same'),
                     rows=[dict(request_id=1), dict(request_id=2)])
        m.validate_audit(result, audit)
        audit['provenance']['client_sha256']='different'
        with self.assertRaises(ValueError): m.validate_audit(result, audit)
        audit['provenance']['client_sha256']='same'
        audit['rows'][1]['request_id']=1
        with self.assertRaises(ValueError): m.validate_audit(result, audit)
        audit['rows'][1]['request_id']=2
        audit['provenance']['allow_partial_trace']=True
        with self.assertRaises(ValueError): m.validate_audit(result, audit)

if __name__ == '__main__': unittest.main()
