import importlib.util
import pathlib
import unittest

path = pathlib.Path(__file__).resolve().parents[1] / 'pmu_breakdown.py'
spec = importlib.util.spec_from_file_location('pmu_breakdown', path)
pmu = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pmu)

class CounterAttribution(unittest.TestCase):
    def test_partition_conserves_counts(self):
        row = {f'pmu_{stage}_{i}': str(1000*(j+1)*(i+1))
               for j,stage in enumerate(pmu.STAGES) for i in range(4)}
        total = pmu.delta(row,'rx_exit','tx_exit')
        parts = [pmu.delta(row,a,b) for a,b in zip(pmu.STAGES[1:],pmu.STAGES[2:])]
        for event in pmu.EVENTS:
            self.assertEqual(total[event],sum(x[event] for x in parts))

    def test_invalid_counter_rejected(self):
        row = {f'pmu_{stage}_{i}': str(value) for stage,value in [('rx_exit',100),('tx_exit',90)] for i in range(4)}
        with self.assertRaises(ValueError):
            pmu.delta(row,'rx_exit','tx_exit')

    def test_excluding_bad_instructions_keeps_other_events(self):
        row = {f'pmu_{stage}_{i}': str(value) for stage,value in [('rx_exit',100),('tx_exit',110)] for i in range(4)}
        row['pmu_tx_exit_0']='90'
        result=pmu.delta(row,'rx_exit','tx_exit',True)
        self.assertNotIn('instructions',result)
        self.assertEqual(result['l1d_refill'],10)
        row['pmu_tx_exit_2']='90'
        with self.assertRaises(ValueError):
            pmu.delta(row,'rx_exit','tx_exit',True)

    def test_percentiles(self):
        self.assertEqual(pmu.percentiles([3,1,2])['p50'],2)
        self.assertEqual(pmu.percentiles([3,1,2])['p100'],3)

if __name__=='__main__':
    unittest.main()
