"""BDK ECI gate and recovery policy, without board access."""
import argparse
import json
from pathlib import Path
import sys
import tempfile
import pexpect
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import boot

QLM = 'CDR lock QLM8:1 QLM9:1 QLM10:1 QLM11:1 QLM12:1 QLM13:1\r\n'
LANES = 'N0.CCPI Lanes([] is good):' + ''.join(f'[{i}]' for i in range(24)) + '\r\n'


class BootTests(unittest.TestCase):
    def test_complete_bdk_initialization(self):
        self.assertIn('ccpi_lanes', boot.validate_eci_bringup('Starting ECI links\r\n' + QLM + LANES))

    def test_cdr_lock_without_lanes_is_failure(self):
        # Observed on the first 512110 boot that aborted in probe_versions.
        with self.assertRaisesRegex(boot.EciBringupError, 'Missing BDK CCPI'):
            boot.validate_eci_bringup('Starting ECI links\r\n' + QLM)

    def test_missing_unlocked_or_partial_initialization(self):
        for text in (LANES, QLM.replace('QLM11:1', 'QLM11:0') + LANES,
                     QLM + LANES.replace('[23]', ''), QLM + LANES.replace('[4]', '(4)')):
            with self.subTest(text=text), self.assertRaises(boot.EciBringupError):
                boot.validate_eci_bringup(text)

    def test_expect_gate_checks_bdk_before_linux_login(self):
        for transcript, valid in [(QLM + LANES, True), (QLM, False)]:
            with self.subTest(valid=valid):
                child = pexpect.spawn(sys.executable, ['-c',
                    'import sys; sys.stdout.write(' + repr(transcript + 'Initialize BGX\nlogin:') + '); sys.stdout.flush()'],
                    encoding='utf-8')
                try:
                    if valid:
                        _, login_ready = boot.expect_eci_bringup(child)
                        self.assertFalse(login_ready)
                        child.expect('login:', timeout=5)
                    else:
                        with self.assertRaises(boot.EciBringupError):
                            boot.expect_eci_bringup(child)
                finally:
                    child.close()

    def args(self, root, **overrides):
        return argparse.Namespace(logs=Path(root) / 'logs', boot_attempts=3,
                                  resume_held=overrides.get('resume_held', False),
                                  cold_start=overrides.get('cold_start', False))

    def test_failed_resume_retries_full_reset_and_preserves_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            args = self.args(root, resume_held=True, cold_start=True)
            with patch.object(boot, 'boot_once', side_effect=[boot.EciBringupError('missing lanes'), {'ok': True}]) as once:
                boot.run_attempts(args, ['program'])
            self.assertEqual(once.call_count, 2)
            retry = once.call_args_list[1].args[0]
            self.assertFalse(retry.resume_held)
            self.assertFalse(retry.cold_start)
            result = json.loads((args.logs / 'summary.json').read_text())
            self.assertEqual([x['status'] for x in result], ['eci_bringup_failed', 'pass'])
            self.assertTrue((args.logs / 'retry-02').is_dir())

    def test_retry_exhaustion_stays_failed(self):
        with tempfile.TemporaryDirectory() as root:
            args = self.args(root)
            with patch.object(boot, 'boot_once', side_effect=boot.EciBringupError('unlocked')) as once:
                with self.assertRaises(boot.EciBringupError):
                    boot.run_attempts(args, ['program'])
            self.assertEqual(once.call_count, 3)
            self.assertEqual(json.loads((args.logs / 'summary.json').read_text())[-1]['status'], 'eci_bringup_failed')

    def test_programmer_or_other_failure_does_not_retry(self):
        with tempfile.TemporaryDirectory() as root:
            args = self.args(root)
            with patch.object(boot, 'boot_once', side_effect=RuntimeError('program failed')) as once:
                with self.assertRaisesRegex(RuntimeError, 'program failed'):
                    boot.run_attempts(args, ['program'])
            self.assertEqual(once.call_count, 1)


if __name__ == '__main__':
    unittest.main()
