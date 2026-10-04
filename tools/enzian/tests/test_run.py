"""Exercise serial script framing against an actual interactive shell."""
import importlib.util
import pathlib
import sys
import unittest

import pexpect

HERE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
spec = importlib.util.spec_from_file_location('enzian_run', HERE / 'run.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class SerialPayloadTests(unittest.TestCase):
    def run_payload(self, body, corrupt=False):
        with pexpect.spawn('bash', ['--noprofile', '--norc', '-i'],
                           encoding='utf-8', timeout=5, echo=False) as shell:
            shell.expect(r'[$#] ')
            shell.sendline("stty -echo; PS1='TEST_READY# '; PS2='TEST_INPUT> '")
            shell.expect_exact('TEST_READY# ')
            runner.prepare_input(shell, 'TEST_INPUT> ')
            payload = runner.make_payload(body, 'TEST_DONE')
            if corrupt:
                lines = payload.splitlines()
                lines[3] = ('A' if lines[3][0] != 'A' else 'B') + lines[3][1:]
                payload = '\n'.join(lines) + '\n'
            runner.send_payload(shell, payload, 'TEST_INPUT> ')
            shell.expect(r'TEST_DONE:(\d+)\r?\n')
            return int(shell.match.group(1)), shell.before

    def test_long_script_and_literal_shell_characters(self):
        body = "\n".join("# padding $() `quoted` ' \"" for _ in range(300))
        body += "\nprintf '%s\\n' 'literal $HOME $(false) `false`'\nexit 0\n"
        status, output = self.run_payload(body)
        self.assertEqual(status, 0)
        self.assertIn('literal $HOME $(false) `false`', output)

    def test_nonzero_status_survives_framing(self):
        self.assertEqual(self.run_payload('exit 7\n')[0], 7)

    def test_noninteractive_stdin_is_eof(self):
        self.assertEqual(self.run_payload('if read -r value; then exit 1; fi\nexit 0\n')[0], 0)

    def test_corrupt_payload_does_not_execute(self):
        status, output = self.run_payload("printf 'SHOULD_NOT_EXECUTE\\n'\n", corrupt=True)
        self.assertEqual(status, 125)
        self.assertIn('PAYLOAD_CHECKSUM_MISMATCH', output)
        self.assertNotIn('SHOULD_NOT_EXECUTE', output)


if __name__ == '__main__':
    unittest.main()
