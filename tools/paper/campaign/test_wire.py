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

    def test_combined_system_acceptance(self):
        import json,tempfile
        from pathlib import Path
        from unittest.mock import patch
        from .import_loaded import digest
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'rehearsal-unloaded.json').write_text('[]')
            receipt=root/'receipt.json'
            receipt.write_text(json.dumps({'result':{'passed':True,'unloaded_only':True},'hashes':{}}))
            proof=dict(root=str(root),cohorts_sha256=digest(root/'rehearsal-unloaded.json'),
                       validation_receipt=str(receipt),validation_sha256=digest(receipt))
            config=dict(systems=['LH','DPDK','CS-A64'],wire_acceptance=[
                dict(proof,systems=['LH','DPDK']),dict(proof,systems=['CS-A64'])])
            with patch('campaign.import_unloaded.validate_matched') as validate:
                require_capture_backend(config)
                self.assertEqual([c.kwargs['systems'] for c in validate.call_args_list],[('LH','DPDK'),('CS-A64',)])
            config['wire_acceptance'][1]['systems']=['LH']
            with self.assertRaisesRegex(ValueError,'exactly once'):require_capture_backend(config)
