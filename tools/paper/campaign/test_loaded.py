"""Check schedule provenance independently of reply outcomes."""
import csv
import json
from pathlib import Path
import tempfile
import unittest

from .import_loaded import load
from .policy import loss


class Loaded(unittest.TestCase):
    def test_phase_boundaries_loss_late_and_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'samples.csv';summary=root/'samples.csv.open.json'
            rows=[dict(request_id=i+1,benchmark='echo8',ok=1,rpc_status=0,warmup=int(i==0),
                       scheduled_ns=1_000_000_000+i*10_000_000,start_lag_ns=1000,
                       client_call_enter_ns=1_000_001_000+i*10_000_000,
                       client_tx_submit_ns=1_000_002_000+i*10_000_000,
                       client_rx_burst_ns=1_000_004_000+i*10_000_000,duplicates=0,server_rejections=0) for i in range(3)]
            state=dict(schema=2,benchmark='echo8',rate=100,warmup_ns=10_000_000,duration_ns=20_000_000,
                       send_start_ns=1_010_000_000,send_end_ns=1_030_000_000,deadline_ns=10_000_000,
                       transmitted=2,on_time=2,late=0,duplicates=0,generator_misses=0,
                       tx_admission_failures=0,malformed=0,valid=True)
            def write():
                with source.open('w') as stream:
                    writer=csv.DictWriter(stream,fieldnames=rows[0]);writer.writeheader();writer.writerows(rows)
                summary.write_text(json.dumps(state))
            write();actual,latencies=load(source,summary);self.assertEqual(latencies,[3000,3000])
            rows[-1].update(ok=0,rpc_status=2,client_rx_burst_ns=0);state['on_time']=1
            write();actual,latencies=load(source,summary);self.assertEqual(actual['transmitted'],2)
            rows[-1].update(rpc_status=4,client_rx_burst_ns=rows[-1]['client_call_enter_ns']+10_000_001)
            state['late']=1;rows[1]['duplicates']=2;state['duplicates']=2
            write();actual,_=load(source,summary);self.assertEqual(actual['late'],1)
            # Valid SYSTEM_ERR is loss, not malformed traffic or a latency sample.
            rows[-1].update(rpc_status=7,server_rejections=1)
            rows[0].update(ok=0,rpc_status=7,server_rejections=1)
            state.update(late=0,server_rejections=1)
            write();actual,latencies=load(source,summary)
            self.assertEqual(actual['server_rejections'],1)  # excludes warmup
            self.assertEqual(loss(actual),.5)
            self.assertEqual(latencies,[3000])
            # Duplicated rejection replies are preserved but cannot double loss.
            rows[-1].update(duplicates=1,server_rejections=2)
            state.update(duplicates=3,server_rejections=2)
            write();actual,_=load(source,summary);self.assertEqual(loss(actual),.5)
            state['server_rejections']=1;write()
            with self.assertRaisesRegex(ValueError,'counters'):load(source,summary)
            state['server_rejections']=2
            rows[1]['scheduled_ns']+=1;write()
            with self.assertRaisesRegex(ValueError,'schedule'):load(source,summary)
            rows[1]['scheduled_ns']-=1;rows[-1]['client_tx_submit_ns']=state['send_end_ns'];write()
            with self.assertRaisesRegex(ValueError,'scheduled phase'):load(source,summary)


if __name__=='__main__':unittest.main()
