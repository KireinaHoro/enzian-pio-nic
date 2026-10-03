import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('holds', Path(__file__).resolve().parents[1] / 'analyze_rx_holds.py')
holds = importlib.util.module_from_spec(spec)
spec.loader.exec_module(holds)

class HoldTests(unittest.TestCase):
    def test_generated_field_widths_and_reserved_header(self):
        trace_map = {
            'sample': {'payload_width': 40, 'source_width': 2},
            'sources_by_id': {1: {'type': 'lauberhorn_event'}},
            'payload_formats': {'lauberhorn_event': {
                'fields': {'event_id': {'offset': 0, 'width': 3},
                           'reserved_event_id': {'offset': 3, 'width': 2}},
                'events': {'6': 'YieldAccepted'},
                'event_data': {'6': ['CoreID', 'DomainCode']},
                'trace_data_keys': {'CoreID': {'width': 6}, 'DomainCode': {'width': 19}}
            }}
        }
        sample = (1 << 40) | 6 | (7 << 5) | (0x45678 << 11)
        self.assertEqual(holds.event(sample, trace_map),
                         ('YieldAccepted', {'CoreID': 7, 'DomainCode': 0x45678}))

    def test_finite_hold_and_independent_transport(self):
        records = [(10, 'EciRxReadNew', {'CoreID': 1}),
                   (15, 'EciRxDescSent', {'CoreID': 2}),
                   (20, 'TxCmacExit', {}),
                   (30, 'EciRxNackSent', {'CoreID': 1})]
        result = holds.summarize(records, 200_000_000)
        self.assertEqual(result['holds'][0]['cycles'], 20)
        self.assertEqual(result['holds'][0]['microseconds'], 0.1)
        self.assertEqual(result['holds'][0]['other_transport_events_during_hold'],
                         {'EciRxDescSent:core=2': 1, 'TxCmacExit:core=None': 1})

    def test_simultaneous_stalls_with_bypass_and_ordinary_handoff(self):
        records = [(1, 'EciRxReadNew', {'CoreID': 1}),
                   (2, 'EciRxReadNew', {'CoreID': 2}),
                   (10, 'EciRxDescSent', {'CoreID': 0}),
                   (11, 'EciTxSubmit', {'CoreID': 0}),
                   (12, 'YieldAccepted', {'CoreID': 3, 'PreemptOutOfIdle': 0}),
                   (13, 'YieldAccepted', {'CoreID': 4, 'PreemptOutOfIdle': 1}),
                   (20, 'EciRxNackSent', {'CoreID': 1}),
                   (25, 'EciRxNackSent', {'CoreID': 2})]
        result = holds.summarize(records, 1, 15)
        self.assertEqual(result['peer_events_by_simultaneously_stalled_workers']['2'],
                         {'EciRxDescSent:core=0': 1, 'EciTxSubmit:core=0': 1, 'YieldAccepted:core=3': 1})
        self.assertEqual(holds.summarize(records, 1, 50)['peer_events_by_simultaneously_stalled_workers'], {})

    def test_activation_paths_and_physical_dispatch_are_distinct(self):
        records = [(1, 'YieldAccepted', {'CoreID': 2, 'DomainCode': 7, 'PreemptOutOfIdle': 1}),
                   (2, 'SchedulerRequestDispatched', {'CoreID': 2, 'ProcessID': 99}),
                   (3, 'YieldAccepted', {'CoreID': 2, 'DomainCode': 8, 'PreemptOutOfIdle': 0}),
                   (4, 'YieldAccepted', {'CoreID': 3, 'DomainCode': 7, 'PreemptOutOfIdle': 0}),
                   (5, 'SchedulerRequestDispatched', {'CoreID': 3, 'ProcessID': 99})]
        result = holds.summarize(records, 1)
        self.assertEqual(result['accepted_activations'],
                         {'idle:core=2:domain=7': 1, 'ordinary:core=2:domain=8': 1,
                          'ordinary:core=3:domain=7': 1})
        self.assertEqual(result['process_worker_dispatches'],
                         {'process=99:core=2': 1, 'process=99:core=3': 1})

    def test_loss_prevents_false_duration_and_handoff_is_marked(self):
        records = [(1, 'EciRxReadNew', {'CoreID': 1}), (2, 'Lost', {'count': 3}),
                   (4, 'EciRxNackSent', {'CoreID': 1}),
                   (5, 'SchedulerPreemptCore', {'CoreID': 2, 'PreemptOutOfIdle': 0}),
                   (6, 'EciRxReadFirst', {'CoreID': 2}),
                   (7, 'EciRxNackSent', {'CoreID': 2}),
                   (8, 'EciRxReadNew', {'CoreID': 3})]
        result = holds.summarize(records, 1)
        self.assertEqual(result['lost_samples'], 3)
        self.assertEqual(len(result['holds']), 1)
        self.assertTrue(result['holds'][0]['handoff_pending'])
        self.assertEqual(result['unmatched']['nack_without_read'], 1)
        self.assertEqual(result['unmatched']['open_read_at_capture_end'], 1)

    def test_activation_parity_reuse_migration_and_loss(self):
        records = []
        def emit(name, **fields):
            records.append((len(records), name, fields))
        for core, domain, rx, tx in ((1, 7, 0, 1), (1, 8, 1, 0),
                                     (1, 7, 1, 1), (2, 7, 0, 0)):
            emit('ActivationCommitted', CoreID=core, DomainCode=domain, RxNext=rx, TxNext=tx)
            emit('ActivationDelivered', CoreID=core, DomainCode=domain)
            emit('EciRxReadFirst', CoreID=core, CacheLineIndex=rx)
            emit('EciTxAcquire', CoreID=core, CacheLineIndex=tx)
            # Later accesses toggle parity and must not be checked against the snapshot.
            emit('EciRxReadNew', CoreID=core, CacheLineIndex=1-rx)
        emit('ActivationCommitted', CoreID=2, DomainCode=7, RxNext=1, TxNext=0)
        emit('EciRxReadNew', CoreID=2, CacheLineIndex=0)
        emit('Lost', count=1)
        emit('EciTxAcquire', CoreID=2, CacheLineIndex=1)
        emit('ActivationDelivered', CoreID=3, DomainCode=7)
        result = holds.summarize(records, 1)['activation_audit']
        self.assertEqual(result['first_access_parity_checks'], {'rx': 5, 'tx': 4})
        self.assertEqual(len(result['parity_mismatches']), 1)
        self.assertEqual(result['parity_mismatches'][0]['direction'], 'rx')
        self.assertEqual(result['observed_aba_by_core'], {'1': 1})
        self.assertEqual(result['observed_domain_migrations'], {'domain=7:1->2': 1})
        self.assertEqual(len(result['committed_parity_pairs']), 4)

if __name__ == '__main__':
    unittest.main()
