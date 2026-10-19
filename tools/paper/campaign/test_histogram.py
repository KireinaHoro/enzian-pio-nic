import copy
import unittest
from .histogram import Latencies, validate_aggregate

class Histogram(unittest.TestCase):
    def test_pool_without_expansion(self):
        a=Latencies([(100,2),(500,1)])
        self.assertEqual(a.percentile(50),100)
        self.assertEqual(a.percentile(99),492)
        a.extend(Latencies([(200,3)]))
        self.assertEqual(a.percentile(50),200)
        a.extend([100,100])
        self.assertEqual(a.percentile(50),150)
        huge=Latencies([(150,10**12),(250,10**12)])
        self.assertEqual(huge.percentile(50),200)

    def test_accounting(self):
        phase=dict(attempted=100,transmitted=100,on_time=80,late=5,duplicates=2,
            generator_misses=0,tx_admission_failures=0,malformed=0,server_rejections=10,
            lag_count=100,lag_sum_ns=10000,lag_max_ns=100,schedule_violations=0)
        s=dict(schema=3,observation_mode='aggregate',histogram_bin_ns=100,
            tx_phase_boundary='admission-entry',arrival_model='paced-batch',
            timestamp_retention_ns=30_000_000,rx_forward_limit_ns=10_000_000,
            generator_cpus=[16],batch_max=64,batch_target_ns=250000,batch_size=1,
            rate=100,warmup_ns=1_000_000_000,duration_ns=1_000_000_000,
            send_start_ns=2_000_000_000,send_end_ns=3_000_000_000,deadline_ns=10_000_000,
            phases=[dict(phase),dict(phase)],latency_histogram=[[120,79],[100000,1]],**phase)
        self.assertEqual(len(validate_aggregate(s)),80)
        self.assertEqual(validate_aggregate(s).percentile(100),10_000_000)
        for modify in (
                lambda x:x['phases'][0].update(schedule_violations=1),
                lambda x:x['phases'][1].update(attempted=99),
                lambda x:x.update(on_time=79),
                lambda x:x.update(latency_histogram=[[120,78],[100000,1]]),
                lambda x:x.update(latency_histogram=[[120,79],[120,1]]),
                lambda x:x.update(send_end_ns=x['send_end_ns']+1),
                lambda x:x.update(batch_size=2)):
            bad=copy.deepcopy(s);modify(bad)
            with self.assertRaises(ValueError):validate_aggregate(bad)
