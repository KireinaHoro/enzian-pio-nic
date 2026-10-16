"""Image bootstrap rejects wrong artifacts before launching any namespace."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[3]))
from tools.enzian.image import python_script, downloaded_python, PYTHON


class Image(unittest.TestCase):
    def test_verified_mount_and_pinned_python(self):
        with tempfile.TemporaryDirectory() as directory:
            image=Path(directory)/'image with spaces';image.write_bytes(b'image')
            sha=hashlib.sha256(b'image').hexdigest()
            shell=python_script(str(image),sha,'print("payload")')
            with patch('subprocess.run') as run:
                exec(shell.split('\n',1)[1].rsplit('\nLHIMAGE',1)[0],{})
                argv=run.call_args.args[0]
                self.assertEqual(argv[:4],['unshare','--mount','bash','-c'])
                self.assertIn('mount -o loop,ro',argv[4])
                self.assertIn('exec '+PYTHON,argv[4])
                self.assertIn('unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME',argv[4])
                self.assertEqual(argv[-2:],[str(image),'print("payload")'])
                image.write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError,'image hash'):
                    exec(shell.split('\n',1)[1].rsplit('\nLHIMAGE',1)[0],{})
                self.assertEqual(run.call_count,1)

    def test_download_hash_checked_inside_pinned_interpreter(self):
        with tempfile.TemporaryDirectory() as directory:
            image=Path(directory)/'image';image.write_bytes(b'image')
            shell=downloaded_python(str(image),hashlib.sha256(b'image').hexdigest(),
                                    'http://fixture/script','0'*64,"raise AssertionError('executed')")
            with patch('subprocess.run') as run:
                exec(shell.split('\n',1)[1].rsplit('\nLHIMAGE',1)[0],{})
            payload=run.call_args.args[0][-1]
            with patch('urllib.request.urlopen') as download:
                download.return_value.read.return_value=b'incorrect script'
                with self.assertRaisesRegex(ValueError,'script hash'):exec(payload,{})
