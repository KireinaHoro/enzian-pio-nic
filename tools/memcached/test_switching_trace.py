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
            add('SchedulerRequestQueued', HostMsgID=len(records), ProcessID=pid)
            add('SchedulerRequestDispatched', CoreID=core, ProcessID=pid, HostMsgID=len(records)-1)
            add('EciRxReadFirst', CoreID=core, CacheLineIndex=0)
            add('EciTxAcquire', CoreID=core, CacheLineIndex=1)
        return launch, records

    def test_switches_and_rejection_of_missing_yield_loss_and_parity(self):
        launch, records = self.fixture()
        result = analyze(records, launch)
        self.assertEqual(len(result['transitions']), 2)
        self.assertEqual(result['peak_assigned_workers'], {100:1,101:1,102:1})
        self.assertEqual(len(result['queue_latencies']), 4)
        more = list(records)
        more += [(20,'YieldAccepted',dict(CoreID=2,PreemptOutOfIdle=0)),
                 (21,'ActivationCommitted',dict(CoreID=2,DomainCode=100,RxNext=0,TxNext=1)),
                 (22,'SchedulerProcessRun',dict(CoreID=2,ProcessID=100)),
                 (23,'ActivationDelivered',dict(CoreID=2,DomainCode=100)),
                 (24,'EciRxReadFirst',dict(CoreID=2,CacheLineIndex=0)),
                 (25,'EciTxAcquire',dict(CoreID=2,CacheLineIndex=1))]
        # Place appended records after the original fixture.
        more = records + [(t+100,n,f) for t,n,f in more[len(records):]]
        with self.assertRaisesRegex(ValueError, 'declared parallelism'):
            analyze(more, launch)
        launch['processes'][0]['workers'] = 2
        self.assertEqual(analyze(more, launch)['peak_assigned_workers'][100], 2)
        launch['processes'][0]['workers'] = 1
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
