import unittest
from .wire import COMPONENTS, join_durations, require_capture_backend


class Wire(unittest.TestCase):
    def setUp(self):
        self.client = [dict(flow_id=3, request_id=1, rtt_ns=1000)]
        self.wire = [dict(flow_id=3, request_id=1, wire_ns=100)]
        self.cpu = [dict(flow_id=3, request_id=1, cpu_duration_ns=80,
                         components_ns=dict.fromkeys(COMPONENTS, 10))]

    def test_partition_and_uninstrumented(self):
        row = join_durations(self.client, self.wire, self.cpu)[0]
        self.assertEqual(row['components_ns']['cpu_wire'], 20)
        self.assertEqual(sum(row['components_ns'].values()), 100)
        self.assertNotIn('components_ns', join_durations(self.client, self.wire)[0])

    def test_incomplete_duplicate_and_negative(self):
        for wire in ([], self.wire*2, [dict(self.wire[0], flow_id=4)],
                     [dict(self.wire[0], wire_ns=50)], [dict(self.wire[0], wire_ns=1001)]):
            with self.assertRaises(ValueError):
                join_durations(self.client, wire, self.cpu)
        self.cpu[0]['components_ns']['rx_queue'] = 0
        with self.assertRaisesRegex(ValueError, 'conserve'):
            join_durations(self.client, self.wire, self.cpu)

    def test_backend_requires_real_acceptance(self):
        with self.assertRaisesRegex(RuntimeError, 'Validated unloaded'):
            require_capture_backend()

    def test_long_campaign_launch_is_held(self):
        from .test_campaign import controller
        for scope in ('rehearsal', 'full'):
            with self.assertRaisesRegex(RuntimeError, 'Validated unloaded'):
                controller.config_check({'scope': scope})
