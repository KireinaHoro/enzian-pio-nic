import unittest
import tempfile
from pathlib import Path
from .qualification import Qualification as LoadedQualification
from .qualification import temporal_statistics, window_statistics

class Qualification(unittest.TestCase):
    def test_time_windows_preserve_loss_population(self):
        def window(transmitted,on_time,bucket):
            return dict(duration_ns=1000000000,transmitted=transmitted,on_time=on_time,
                latency_histogram=[[bucket,on_time]],lag_count=transmitted,lag_sum_ns=transmitted*100,lag_max_ns=100)
        windows=[window(100,100,10)]*10+[window(100,90,20)]*10
        result=temporal_statistics(dict(windows=windows,window_ns=1000000000,duration_ns=20000000000,deadline_ns=10000000))
        self.assertAlmostEqual(result['whole']['loss'],.05)
        self.assertTrue(result['whole']['p99_infinite'])
        self.assertFalse(result['blocks_10s'][0]['p99_infinite'])
        self.assertTrue(result['blocks_10s'][1]['p99_infinite'])
        self.assertEqual(result['whole']['goodput_rpc_s'],95)
        boundary=window_statistics([window(100,99,10)],10000000)
        self.assertFalse(boundary['p99_infinite'])

    def test_pilot_bracket_and_independent_sustained_seeds(self):
        with tempfile.TemporaryDirectory() as directory:
            q=object.__new__(LoadedQualification);q.root=Path(directory)
            q.plan=dict(pilot_seed=42,seeds=[11,22,33],refinement_points=2)
            calls=[]
            def acquire(case,rate,duration,seed):
                calls.append((rate,duration,seed))
                return dict(request=dict(rate=rate),worker_activity=[10],
                    statistics=dict(whole=dict(loss=.02 if rate>=160000 else 0)))
            q.acquire=acquire
            result=q.run_case(dict(system='CS-A64',payload_bytes=8,workers=1,pilot_rates=[100000,200000]))
            self.assertEqual(result['selected_rate'],150000)
            self.assertEqual(calls[:4],[(100000,10000,42),(200000,10000,42),(150000,10000,42),(175000,10000,42)])
            self.assertEqual(calls[4:],[(150000,60000,11),(150000,60000,22),(150000,60000,33)])
