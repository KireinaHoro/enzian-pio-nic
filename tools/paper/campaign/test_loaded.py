"""Check schedule provenance independently of reply outcomes."""
import csv
import json
from pathlib import Path
import tempfile
import unittest

from .import_loaded import load
from .policy import loss


class Loaded(unittest.TestCase):
    def test_batched_partial_phases(self):
        from .policy import batch_due
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'samples.csv';summary=root/'summary.json'
            # 5 warmup slots rotates the 3 core owners at the phase boundary;
            # 11 measured slots also ends with a partial batch round.
            state=dict(schema=2,tx_phase_boundary='admission-entry',generator_cpus=[16,17,18],
                arrival_model='paced-batch',batch_max=64,batch_target_ns=1_000_000,batch_size=3,
                benchmark='echo8',rate=10000,warmup_ns=500_000,duration_ns=1_100_000,
                send_start_ns=500_000,send_end_ns=1_600_000,deadline_ns=10_000_000,
                transmitted=11,on_time=11,late=0,duplicates=0,generator_misses=0,
                tx_admission_failures=0,malformed=0,valid=True)
            rows=[]
            for i in range(16):
                warm=i<5;due=batch_due(0 if warm else 500_000,i if warm else i-5,10000,3,3,5 if warm else 11)
                rows.append(dict(request_id=i+1,benchmark='echo8',ok=1,rpc_status=0,warmup=int(warm),
                    scheduled_ns=due,start_lag_ns=100,client_call_enter_ns=due+100,
                    client_tx_admit_enter_ns=due+200,client_tx_submit_ns=due+300,
                    client_rx_burst_ns=due+2000,duplicates=0))
            def write():
                with source.open('w') as f:
                    writer=csv.DictWriter(f,fieldnames=rows[0]);writer.writeheader();writer.writerows(rows)
                summary.write_text(json.dumps(state))
            write();actual,latencies=load(source,summary)
            self.assertEqual(len(latencies),11)
            self.assertEqual(actual['batch_size'],3)
            rows[-1]['scheduled_ns']+=1;write()
            with self.assertRaisesRegex(ValueError,'schedule'):load(source,summary)
            rows[-1]['scheduled_ns']-=1
            rows[3]['client_tx_admit_enter_ns']+=1;write()
            with self.assertRaisesRegex(ValueError,'share a TX call'):load(source,summary)
            rows[3]['client_tx_admit_enter_ns']-=1;state['batch_size']=4;write()
            with self.assertRaisesRegex(ValueError,'batch configuration'):load(source,summary)

    def test_tx_entry_boundary_and_bounded_return(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'samples.csv';summary=root/'summary.json'
            # Last slot begins at 9 ms; TX entry is just before 10 ms.
            state=dict(schema=2,tx_phase_boundary='admission-entry',generator_cpus=[16,17],
                benchmark='echo8',rate=1000,warmup_ns=0,duration_ns=10_000_000,
                send_start_ns=0,send_end_ns=10_000_000,deadline_ns=10_000_000,
                transmitted=10,on_time=10,late=0,duplicates=0,generator_misses=0,
                tx_admission_failures=0,malformed=0,valid=True)
            rows=[dict(request_id=i+1,benchmark='echo8',ok=1,rpc_status=0,warmup=0,
                scheduled_ns=i*1_000_000,start_lag_ns=100,
                client_call_enter_ns=i*1_000_000+100,client_tx_admit_enter_ns=i*1_000_000+200,
                client_tx_submit_ns=i*1_000_000+300,client_rx_burst_ns=i*1_000_000+2000,
                duplicates=0) for i in range(10)]
            rows[-1].update(client_tx_admit_enter_ns=9_999_900,
                client_tx_submit_ns=10_000_170,client_rx_burst_ns=10_002_000)
            def write():
                with source.open('w') as f:
                    writer=csv.DictWriter(f,fieldnames=rows[0]);writer.writeheader();writer.writerows(rows)
                summary.write_text(json.dumps(state))
            write();load(source,summary)
            rows[-1]['client_tx_admit_enter_ns']=10_000_000;write()
            with self.assertRaisesRegex(ValueError,'TX admission'):load(source,summary)
            rows[-1]['client_tx_admit_enter_ns']=9_999_900
            rows[-1]['client_tx_submit_ns']=11_000_000;write()
            with self.assertRaisesRegex(ValueError,'overran'):load(source,summary)
            # Old observations keep their original stricter return-boundary rule.
            state.pop('tx_phase_boundary');rows[-1]['client_tx_submit_ns']=10_000_170;write()
            with self.assertRaisesRegex(ValueError,'scheduled phase'):load(source,summary)

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
