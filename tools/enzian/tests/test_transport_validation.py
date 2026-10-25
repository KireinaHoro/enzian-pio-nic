"""Acceptance manifests must preserve failed DUT state before another cold boot."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


generator = load('transport_manifest', HERE / 'transport_validation.py')
harness = load('hardware_acceptance', HERE / 'test.py')


class AcceptanceTests(unittest.TestCase):
    def cases(self):
        return generator.manifest(SimpleNamespace(
            revision='1' * 40, image='/scratch/test image.img', program=['program', 'bit'],
            cidr='192.168.129.200/18', mac='0c:53:31:03:01:c8',
            peer='zuestoll14', peer_interface='enP5p1s0'))

    def test_shell_payloads_and_protocol_coverage(self):
        cases = self.cases()
        self.assertEqual(len(cases), 6)
        for case in cases:
            for field in ('cpu', 'client', 'post_cpu'):
                if field in case:
                    subprocess.run(['bash', '-n'], input=case[field], text=True, check=True)
        raw = next(c for c in cases if c['name'] == 'tcp-transport')
        self.assertIn('TCP_TRANSPORT_PASS', raw['post_cpu'])
        self.assertIn('ECONNREFUSED', raw['client'])
        protocol = next(c for c in cases if c['name'] == 'tcp-protocol')
        self.assertIn('tcp_reorder.py', protocol['client'])

    def test_failed_post_check_stops_before_next_reset(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / 'cases.json'
            manifest.write_text(json.dumps(self.cases()))
            calls = []

            def run_step(command, log, timeout):
                calls.append(log.name)
                log.write_text('')
                return 9 if log.name == 'post-cpu-output.log' else 0

            with patch.object(sys, 'argv', ['test.py', str(manifest), '--logs', str(root / 'logs')]), \
                 patch.object(harness, 'run_step', run_step), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(harness.main(), 1)
            results = json.loads((root / 'logs/summary.json').read_text())
            self.assertEqual(len(results), 1)
            self.assertEqual(results[0]['post_cpu_exit'], 9)
            self.assertEqual(results[0]['status'], 'fail')
            self.assertEqual(calls.count('boot-output.log'), 1)


if __name__ == '__main__':
    unittest.main()
