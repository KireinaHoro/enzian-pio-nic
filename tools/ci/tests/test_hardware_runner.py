"""Exercise hardware orchestration with fake Docker/Vivado; never run PnR."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class HardwareRunnerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.bundle = self.work / 'bundle with spaces'
        (self.bundle / 'tools/hardware').mkdir(parents=True)
        shutil.copytree(ROOT / 'tools/hardware', self.bundle / 'tools/hardware', dirs_exist_ok=True)
        (self.bundle / 'vivado/eci').mkdir(parents=True)
        shutil.copy(ROOT / 'vivado/eci/container.yml', self.bundle / 'vivado/eci/container.yml')
        (self.bundle / 'vivado/eci/ci_build.tcl').touch()
        (self.bundle / 'git-revision').write_text('a' * 40)
        self.install = self.work / 'installation'
        (self.install / 'bin').mkdir(parents=True)
        (self.install / 'settings64.sh').write_text(':\n')
        self.env = dict(os.environ, VIVADO_ROOT=str(self.install), VIVADO_INSTALLATION=str(self.install),
                        CALLS=str(self.work / 'calls'))
        self.output = self.work / 'output with spaces'

    def executable(self, path, body, interpreter='bash'):
        path.write_text('#!' + shutil.which(interpreter) + '\n' + body)
        path.chmod(0o755)

    def run_script(self, name, mode):
        return subprocess.run(['bash', str(ROOT / 'tools/hardware' / name),
                               str(self.bundle), str(self.output), mode],
                              env=self.env, capture_output=True, text=True)

    def fake_vivado(self, status=0):
        self.executable(self.install / 'bin/vivado', f'echo "$*" >> "$CALLS"\nexit {status}\n')

    def test_project_only_forwarding_and_repeat_refusal(self):
        self.fake_vivado()
        first = self.run_script('run-vivado.sh', 'project-only')
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn('project-only', (self.work / 'calls').read_text())
        self.assertTrue((self.output / 'vivado-build.log').exists())
        second = self.run_script('run-vivado.sh', 'project-only')
        self.assertNotEqual(second.returncode, 0)
        self.assertEqual(len((self.work / 'calls').read_text().splitlines()), 1)

    def test_vivado_failure_propagates_through_tee(self):
        self.fake_vivado(23)
        self.assertEqual(self.run_script('run-vivado.sh', 'build').returncode, 23)

    def test_report_requires_checkpoint_and_completion(self):
        self.fake_vivado()
        self.assertNotEqual(self.run_script('run-vivado.sh', 'report').returncode, 0)
        project = self.output / 'eci/vivadoProject.dest'
        project.mkdir(parents=True)
        (project / 'shell_lauberhorn-eci_routed.dcp').touch()
        result = self.run_script('run-vivado.sh', 'report')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('incomplete', result.stderr)

    def test_docker_mounts_pin_and_exit_status(self):
        bindir = self.work / 'bin'
        bindir.mkdir()
        self.executable(bindir / 'docker', '''import os, sys, json
with open(os.environ['CALLS'], 'w') as f: json.dump(sys.argv[1:], f)
sys.exit(31)
''', 'python3')
        self.env['PATH'] = str(bindir) + ':' + self.env['PATH']
        self.env['XILINXD_LICENSE_FILE'] = '2100@example'
        result = self.run_script('docker-vivado.sh', 'project-only')
        self.assertEqual(result.returncode, 31, result.stderr)
        args = json.loads((self.work / 'calls').read_text())
        self.assertIn(f'type=bind,src={self.bundle},dst=/bundle,readonly', args)
        self.assertIn(f'type=bind,src={self.output},dst=/work', args)
        self.assertIn('XILINXD_LICENSE_FILE', args)
        self.assertEqual(args[-1], 'project-only')
        self.assertTrue(any('@sha256:' in arg for arg in args))
        self.assertNotIn('--privileged', args)
        self.assertNotEqual(self.run_script('docker-vivado.sh', 'project-only').returncode, 0)

    def test_build_report_status_precedence(self):
        bindir = self.work / 'bin'
        bindir.mkdir()
        self.executable(bindir / 'docker', '''import os, sys
from pathlib import Path
mode = sys.argv[-1]
with open(os.environ['CALLS'], 'a') as f: f.write(mode + "\\n")
if mode == 'build':
    mount = next(a for a in sys.argv if a.endswith('dst=/work'))
    output = Path(mount.split('src=', 1)[1].split(',dst=', 1)[0])
    project = output / 'eci/vivadoProject.dest'
    project.mkdir(parents=True)
    (project / 'shell_lauberhorn-eci_routed.dcp').touch()
sys.exit(int(os.environ['BUILD_STATUS' if mode == 'build' else 'REPORT_STATUS']))
''', 'python3')
        self.env['PATH'] = str(bindir) + ':' + self.env['PATH']
        for build, report, expected in ((0, 0, 0), (23, 0, 23), (0, 31, 31), (23, 31, 23)):
            with self.subTest(build=build, report=report):
                self.output = self.work / f'output-{build}-{report}'
                self.env.update(BUILD_STATUS=str(build), REPORT_STATUS=str(report))
                result = self.run_script('docker-vivado.sh', 'build')
                self.assertEqual(result.returncode, expected, result.stderr)
        self.assertEqual((self.work / 'calls').read_text().splitlines(), ['build', 'report'] * 4)

    def test_invalid_mode_is_rejected(self):
        for script in ('run-vivado.sh', 'docker-vivado.sh'):
            self.assertEqual(self.run_script(script, 'unknown').returncode, 2)


if __name__ == '__main__':
    unittest.main()
