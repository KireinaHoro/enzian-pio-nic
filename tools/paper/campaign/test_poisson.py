import copy
import csv
import json
from pathlib import Path
import tempfile
import unittest
from .poisson import batches, schedule, VERSION, WARMUP_STREAM
from .import_loaded import load
from .histogram import validate_aggregate


class Poisson(unittest.TestCase):
    def test_c_reference_and_random_counts(self):
        values=list(batches(3000000,100000000,1000,42))
        self.assertEqual(sum(n for _,n in values),300195)
        self.assertEqual(values,list(batches(3000000,100000000,1000,42)))
        self.assertNotEqual(values,list(batches(3000000,100000000,1000,43)))
        self.assertEqual(values[:2],[(0,2),(1000,1)])

    def summary(self):
        counts=[]
        for duration,seed in [(500000,42^WARMUP_STREAM),(1000000,42)]:
            c=[0,0]
            for slot,(_,n) in enumerate(batches(10000,duration,1000,seed)):c[slot%2]+=n
            counts.append(c)
        return dict(schema=2,arrival_model='slotted-poisson',schedule_version=VERSION,seed=42,
            max_send_lag_ns=10000,slot_ns=1000,tx_burst_max=64,generator_cpus=[16,17],
            tx_phase_boundary='admission-entry',benchmark='echo8',rate=10000,
            warmup_ns=500000,duration_ns=1000000,send_start_ns=1000000,send_end_ns=2000000,
            warmup_count=sum(counts[0]),scheduled_count=sum(counts[1]),phase_core_counts=counts,
            deadline_ns=10000000,transmitted=sum(counts[1]),on_time=0,late=0,duplicates=0,
            generator_misses=0,tx_admission_failures=0,malformed=0,valid=True)

    def test_detailed_replays_each_deadline_and_phase(self):
        s=self.summary()
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);rows=[]
            for i,(due,warm) in enumerate(schedule(s)):
                rows.append(dict(request_id=i+1,benchmark='echo8',ok=0,rpc_status=2,
                    warmup=int(warm),scheduled_ns=due,start_lag_ns=100,duplicates=0,
                    client_call_enter_ns=due+100,client_tx_admit_enter_ns=due+200,
                    client_tx_submit_ns=due+300,client_rx_burst_ns=0))
            def write():
                (p/'summary.json').write_text(json.dumps(s))
                with (p/'samples.csv').open('w') as f:
                    w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(rows)
            write();trial,_=load(p/'samples.csv',p/'summary.json')
            self.assertEqual(trial['transmitted'],s['scheduled_count'])
            measured=[r for r in rows if not r['warmup']]
            measured[0].update(rpc_status=4,client_rx_burst_ns=measured[0]['client_call_enter_ns']+10000001)
            s['late']=1
            measured[1].update(rpc_status=7,server_rejections=1,client_rx_burst_ns=measured[1]['client_call_enter_ns']+1000)
            for row in rows:row.setdefault('server_rejections',0)
            s['server_rejections']=1
            write();actual,values=load(p/'samples.csv',p/'summary.json')
            self.assertEqual((actual['transmitted'],actual['late'],actual['server_rejections'],values),
                             (s['scheduled_count'],1,1,[]))
            rows[-1]['scheduled_ns']+=100;write()
            with self.assertRaisesRegex(ValueError,'schedule'):load(p/'samples.csv',p/'summary.json')
            rows[-1]['scheduled_ns']-=100
            rows[-1]['client_tx_submit_ns']=rows[-1]['scheduled_ns']+10001;write()
            with self.assertRaisesRegex(ValueError,'lag bound'):load(p/'samples.csv',p/'summary.json')

    def test_aggregate_random_counts_and_rejection_gates(self):
        s=self.summary();s.update(schema=3,observation_mode='aggregate',histogram_bin_ns=100,
            timestamp_retention_ns=30000000,rx_forward_limit_ns=10000000,phases=[{},{}])
        for i,phase in enumerate(s['phases']):
            n=s['warmup_count'] if i==0 else s['scheduled_count']
            phase.update(attempted=n,transmitted=n,on_time=0,late=0,duplicates=0,
                generator_misses=0,tx_admission_failures=0,malformed=0,server_rejections=0,
                lag_count=n,lag_sum_ns=n*100,lag_max_ns=100,schedule_violations=0)
        s['latency_histogram']=[]
        s['server_rejections']=0
        validate_aggregate(s)
        bad=copy.deepcopy(s);bad['phases'][1]['attempted']+=1
        with self.assertRaises(ValueError):validate_aggregate(bad)
        bad=copy.deepcopy(s);bad['slot_ns']=0
        with self.assertRaisesRegex(ValueError,'Poisson'):validate_aggregate(bad)
        bad=copy.deepcopy(s);bad['phases'][1]['lag_max_ns']=10001
        with self.assertRaises(ValueError):validate_aggregate(bad)
