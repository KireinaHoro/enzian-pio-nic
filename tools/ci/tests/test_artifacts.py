"""Regression checks for fresh-output CI publication; no Nix builds are run."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.bin = self.work / 'bin'
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'])

    def tool(self, name, body):
        path = self.bin / name
        path.write_text('#!' + shutil.which('bash') + '\nset -eu\n' + body)
        path.chmod(0o755)

    def run_script(self, script, *args):
        return subprocess.run(['bash', str(ROOT / 'tools/ci' / script), *args],
                              cwd=self.work, env=self.env, capture_output=True, text=True)

    def test_ci_repeat_preserves_original_without_invoking_nix(self):
        source = self.work / 'package'
        source.mkdir()
        (source / 'version').write_text('first')
        self.env['PACKAGE'] = str(source)
        self.tool('nix', 'echo called >> calls\nwhile [[ $1 != --out-link ]]; do shift; done\nln -s "$PACKAGE" "$2"\n')
        first = self.run_script('nix-build.sh', 'example', '.#example')
        self.assertEqual(first.returncode, 0, first.stderr)
        (source / 'version').write_text('second')
        second = self.run_script('nix-build.sh', 'example', '.#example')
        self.assertNotEqual(second.returncode, 0)
        self.assertIn('already exists', second.stderr)
        self.assertEqual((self.work / 'calls').read_text(), 'called\n')
        output = self.work / 'out/ci/example/output'
        self.assertEqual((output / 'version').read_text(), 'first')
        self.assertFalse((output / 'result').exists())

    def test_failure_has_no_published_output(self):
        self.tool('nix', 'echo build-failed\nexit 7\n')
        result = self.run_script('nix-build.sh', 'failure', '.#example')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.work / 'out/ci/failure/output').exists())
        self.assertIn('build-failed', (self.work / 'out/ci/failure/build.log').read_text())

    def test_names_cannot_escape_output_directory(self):
        for name in ('../escape', '/tmp/escape', '.', ''):
            self.assertEqual(self.run_script('nix-build.sh', name, '.#x').returncode, 2)

    def test_failure_collects_reported_nix_directory_and_replay_evidence(self):
        for idx, location in enumerate(('nix/var/nix/builds/nix-123-456',
                                        'tmp/nix-build-lauberhorn-example.drv-0')):
            with self.subTest(location=location):
                failed = self.work / location
                artifacts = failed / 'source/out/test with spaces'
                artifacts.mkdir(parents=True)
                names = ('sim_transcript.log.gz', 'wave.fst', 'simulation-seeds.txt',
                         'simulation-runtime.txt', 'simulation-inputs.sha256',
                         'lauberhorn_trace.pcapng', 'test-report.xml')
                for name in names:
                    (artifacts / name).write_text(name)
                (artifacts / 'compiler-product.o').write_text('omit')
                self.env['FAILED_DIR'] = str(failed)
                self.tool('nix', '''printf 'note: keeping build directory "%s"\\n' "$FAILED_DIR"
exit 7
''')
                result = self.run_script('nix-build.sh', f'retained-{idx}', '.#x')
                self.assertNotEqual(result.returncode, 0)
                dest = self.work / f'out/ci/retained-{idx}/failed/source/out/test with spaces'
                self.assertEqual(sorted(p.name for p in dest.iterdir()), sorted(names))
                for name in names:
                    self.assertEqual((dest / name).read_text(), name)

    def prepare_tool(self):
        self.env['CI_COMMIT_SHA'] = 'a' * 40
        self.env['BUNDLE_REV'] = 'a' * 40
        self.tool('ci-build', '''echo called >> calls
mkdir -p "out/ci/$1"
if [[ $1 == eci-inputs ]]; then
  mkdir -p "out/ci/$1/output/generated"
  echo "$BUNDLE_REV" > "out/ci/$1/output/git-revision"
  echo rtl > "out/ci/$1/output/generated/NicEngine.v"
else
  echo image > "out/ci/$1/output"
fi
''')

    def test_prepare_requires_fresh_artifacts(self):
        self.prepare_tool()
        first = self.run_script('prepare-eci.sh')
        self.assertEqual(first.returncode, 0, first.stderr)
        second = self.run_script('prepare-eci.sh')
        self.assertNotEqual(second.returncode, 0)
        self.assertEqual((self.work / 'calls').read_text().count('called'), 2)
        self.assertTrue((self.work / 'out/eci/vivado-inputs/generated/NicEngine.v').is_file())
        self.assertFalse((self.work / 'out/eci/vivado-inputs/output').exists())

    def test_prepare_rejects_wrong_revision_before_publishing(self):
        self.prepare_tool()
        self.env['BUNDLE_REV'] = 'b' * 40
        result = self.run_script('prepare-eci.sh')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('revision mismatch', result.stderr)
        self.assertFalse((self.work / 'out/deploy.img').exists())


if __name__ == '__main__':
    unittest.main()
