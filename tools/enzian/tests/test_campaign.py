import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('campaign', Path(__file__).resolve().parents[1] / 'test.py')
campaign = importlib.util.module_from_spec(spec)
spec.loader.exec_module(campaign)


class CampaignTests(unittest.TestCase):
    def run_cases(self, failed_stage):
        calls = []
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root/'cases.json'
            manifest.write_text(json.dumps([dict(name=name, revision='a'*40, image='/image', program=['program'], client='client') for name in ['first', 'second']]))
            def step(command, log, timeout):
                stage = 'boot' if 'boot-output' in log.name else 'cpu' if 'cpu-output' in log.name else 'client'
                calls.append(stage)
                log.write_text('')
                return 1 if stage == failed_stage else 0
            with patch('sys.argv', ['test.py', str(manifest), '--logs', str(root/'logs'), '--repeats', '2']), patch.object(campaign, 'run_step', step), contextlib.redirect_stdout(io.StringIO()):
                result = campaign.main()
            summary = json.loads((root/'logs/summary.json').read_text())
        return result, calls, summary

    def test_cpu_failure_stops_before_any_next_reset(self):
        result, calls, summary = self.run_cases('cpu')
        self.assertEqual(result, 1)
        self.assertEqual(calls, ['boot', 'cpu'])
        self.assertEqual(len(summary), 1)
        self.assertEqual(summary[0]['status'], 'fail')

    def test_client_failure_stops_before_any_next_reset(self):
        result, calls, summary = self.run_cases('client')
        self.assertEqual(result, 1)
        self.assertEqual(calls, ['boot', 'cpu', 'client'])
        self.assertEqual(len(summary), 1)

    def test_successful_trials_still_repeat(self):
        result, calls, summary = self.run_cases(None)
        self.assertEqual(result, 0)
        self.assertEqual(calls, ['boot', 'cpu', 'client'] * 4)
        self.assertTrue(all(x['status'] == 'pass' for x in summary))


if __name__ == '__main__':
    unittest.main()
