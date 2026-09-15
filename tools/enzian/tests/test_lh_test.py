"""Portable helper contract checks; never access a NIC or load a real module."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'lh-test.sh'


class HelperTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        bindir = self.root / 'app/bin'
        bindir.mkdir(parents=True)
        executable = bindir / 'default'
        executable.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@"\nexit 37\n')
        executable.chmod(0o755)
        (bindir / 'alternate').symlink_to(executable)
        manifest = self.root / 'manifest.json'
        manifest.write_text(json.dumps({'applications': {
            'demo': {'path': str(bindir.parent), 'entryPoint': str(executable)},
            'no-default': {'path': str(bindir.parent), 'entryPoint': None},
        }}))
        self.env = dict(os.environ, LH_MANIFEST=str(manifest))

    def run_helper(self, *args):
        return subprocess.run(['bash', str(HELPER), *args], env=self.env,
                              capture_output=True, text=True)

    def test_info_json(self):
        result = self.run_helper('info', '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('demo', json.loads(result.stdout)['applications'])

    def test_status_and_argument_preservation(self):
        result = self.run_helper('run', 'demo', '--', 'two words', '--flag', '')
        self.assertEqual(result.returncode, 37)
        self.assertEqual(result.stdout, 'two words\n--flag\n\n')

    def test_explicit_executable(self):
        result = self.run_helper('run', 'no-default', '--executable', 'alternate', 'ok')
        self.assertEqual(result.returncode, 37)
        self.assertEqual(result.stdout, 'ok\n')

    def test_errors(self):
        for args in [(), ('unknown',), ('info', 'extra'), ('load', 'extra'),
                     ('configure',), ('run',), ('run', 'missing'),
                     ('run', 'no-default'), ('run', 'demo', '--executable'),
                     ('run', 'demo', '--executable', '../default'),
                     ('run', 'demo', '--executable', 'missing')]:
            with self.subTest(args=args):
                result = self.run_helper(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertTrue(result.stderr)


if __name__ == '__main__':
    unittest.main()
