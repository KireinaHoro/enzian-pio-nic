"""Synthetic trace rejection checks, not hardware evidence."""
import unittest
from analyze_switching import analyze


class SwitchingTraceTests(unittest.TestCase):
    def fixture(self):
        launch = dict(case=dict(physical_workers=2), worker_cpus=[44,45],
                      processes=[dict(pid=p,workers=1) for p in (100,101,102)])
        records = []
        def add(name, **fields):
            records.append((len(records), name, fields))
        for core,pid,idle in [(1,100,1),(2,101,1),(1,102,0),(1,100,0)]:
            add('YieldAccepted', CoreID=core, DomainCode=pid, PreemptOutOfIdle=idle)
            add('ActivationCommitted', CoreID=core, DomainCode=pid, RxNext=0, TxNext=1)
            add('SchedulerProcessRun', CoreID=core, ProcessID=pid)
            add('ActivationDelivered', CoreID=core, DomainCode=pid)
            add('SchedulerRequestDispatched', CoreID=core, ProcessID=pid)
            add('EciRxReadFirst', CoreID=core, CacheLineIndex=0)
            add('EciTxAcquire', CoreID=core, CacheLineIndex=1)
        return launch, records

    def test_switches_and_rejection_of_missing_yield_loss_and_parity(self):
        launch, records = self.fixture()
        self.assertEqual(len(analyze(records, launch)['transitions']), 2)
        with self.assertRaisesRegex(ValueError, 'ordinary yield'):
            analyze([r for r in records if r[1] != 'YieldAccepted'], launch)
        with self.assertRaisesRegex(ValueError, 'loss'):
            analyze(records+[(99,'Lost',dict(count=1))], launch)
        with self.assertRaisesRegex(ValueError, 'incomplete activation'):
            analyze([r for r in records if r[1] != 'ActivationDelivered'], launch)
        broken = [(t,n,dict(f,CacheLineIndex=1) if n=='EciRxReadFirst' else f)
                  for t,n,f in records]
        with self.assertRaisesRegex(ValueError, 'parity'):
            analyze(broken, launch)


if __name__ == '__main__':
    unittest.main()
