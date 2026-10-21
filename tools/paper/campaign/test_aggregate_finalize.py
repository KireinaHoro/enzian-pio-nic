"""Full-grid validation accepts compact observations without relaxing loss gates."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from .finalize import verify_loaded
from .import_loaded import load
from .policy import SYSTEMS, SIZES, WORKERS

class AggregateFinalize(unittest.TestCase):
    def test_full_matrix_and_sustained_boundaries(self):
        self.check_matrix(10,60)

    def test_manifest_durations(self):
        self.check_matrix(3,7)

    def check_matrix(self,measurement_seconds,confirmation_seconds):
        settings={'loaded':{'measurement_ms':measurement_seconds*1000,
                            'confirmation_ms':confirmation_seconds*1000}}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            def trial(name,rate,seconds,missing=0,size=8):
                def phase(n):
                    return dict(attempted=n,transmitted=n,on_time=n,late=0,duplicates=0,
                        generator_misses=0,tx_admission_failures=0,malformed=0,server_rejections=0,
                        lag_count=n,lag_sum_ns=n*100,lag_max_ns=100,schedule_violations=0)
                measured=phase(rate*seconds);measured['on_time']-=missing
                s=dict(schema=3,observation_mode='aggregate',histogram_bin_ns=100,
                    timestamp_retention_ns=30_000_000,rx_forward_limit_ns=10_000_000,
                    generator_cpus=[16],arrival_model='paced-batch',batch_max=64,
                    batch_target_ns=250000,batch_size=1,tx_phase_boundary='admission-entry',
                    benchmark='echo'+str(size),rate=rate,warmup_ns=2_000_000_000,duration_ns=seconds*10**9,
                    send_start_ns=2_000_000_000,send_end_ns=(2+seconds)*10**9,
                    deadline_ns=10_000_000,valid=True,phases=[phase(2*rate),measured],
                    latency_histogram=[[120,measured['on_time']]],**measured)
                csv=root/(name+'.csv');csv.write_text('request_id\n')
                summary=root/(name+'.open.json');summary.write_text(json.dumps(s))
                return load(csv,summary)[0]
            records=[];plans=[]
            for size in SIZES:
                low=trial(f'low-{size}',100,measurement_seconds,size=size)
                high=trial(f'high-{size}',150,measurement_seconds,30,size=size)
                peak=trial(f'peak-{size}',100,confirmation_seconds,size=size)
                points=[dict(rate=100,trials=[low]),dict(rate=150,trials=[high])]
                confirmed=dict(rate=100,trials=[peak])
                for system in SYSTEMS:
                    for workers in WORKERS:
                        key=dict(system=system,size=size,workers=workers)
                        plans.append(dict(key,rates=[100,150]))
                        records.append(dict(key,points=points,confirmations=[confirmed],confirmed=confirmed))
            verify_loaded(records,plans,measurement=settings)
            extra=copy.deepcopy(records)
            extra[0]['points'][0]['trials']*=3
            with self.assertRaisesRegex(ValueError,'repetition count'):verify_loaded(extra,plans,measurement=settings)
            bad=copy.deepcopy(records)
            bad[0]['points'][1]['trials'][0]['on_time']+=1
            with self.assertRaisesRegex(ValueError,'evidence changed'):verify_loaded(bad,plans,measurement=settings)
