"""Exercise portable experiment archives and reject unsafe/corrupted evidence."""
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from .experiment import INDEX, input_file, pack
from .finalize import pack_mode
from .import_loaded import digest


class Experiment(unittest.TestCase):
    def test_roundtrip_deterministic_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root/'source'; source.mkdir()
            (source/'raw').mkdir()
            (source/'raw'/'sample.csv').write_bytes(b'a,b\n1,2\n')
            (source/'unloaded.json').write_text('[{"count": 1}]')
            a, b = root/'a.tar.gz', root/'b.tar.gz'
            pack(source, a)
            (source/'unloaded.json').touch()
            pack(source, b)
            self.assertEqual(a.read_bytes(), b.read_bytes())
            with self.assertRaises(FileExistsError): pack(source, a)
            with input_file(a, 'unloaded.json') as records:
                extracted = records.parent
                self.assertEqual(records.read_bytes(), (source/'unloaded.json').read_bytes())
                self.assertEqual((extracted/'raw/sample.csv').read_bytes(), b'a,b\n1,2\n')
            self.assertFalse(extracted.exists())
            with input_file(source/'unloaded.json', 'unloaded.json') as records:
                self.assertEqual(records, source/'unloaded.json')
            with self.assertRaisesRegex(ValueError, 'no loaded.json'):
                with input_file(a, 'loaded.json'): pass

    def test_reject_unsafe_duplicate_and_corrupt_members(self):
        cases = [('escape', '../escape', tarfile.REGTYPE),
                 ('absolute', '/escape', tarfile.REGTYPE),
                 ('link', 'link', tarfile.SYMTYPE),
                 ('duplicate', 'unloaded.json', tarfile.REGTYPE),
                 ('corrupt', 'raw.csv', tarfile.REGTYPE)]
        with tempfile.TemporaryDirectory() as directory:
            for label, name, kind in cases:
                with self.subTest(label=label):
                    archive = Path(directory)/(label+'.tar.gz')
                    with tarfile.open(archive, 'w:gz') as stream:
                        for entry, data, typ in [('unloaded.json', b'[]', tarfile.REGTYPE),
                                                 (INDEX, b'{}', tarfile.REGTYPE),
                                                 (name, b'bad', kind)]:
                            info = tarfile.TarInfo(entry); info.type = typ
                            info.size = len(data) if typ == tarfile.REGTYPE else 0
                            info.linkname = '/tmp/escape' if typ == tarfile.SYMTYPE else ''
                            stream.addfile(info, io.BytesIO(data))
                    with self.assertRaises(ValueError):
                        with input_file(archive, 'unloaded.json'): pass

    def test_modes_are_separate_with_shared_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source=root/'source'; source.mkdir()
            (source/'raw').mkdir()
            for mode in ('loaded', 'unloaded'):
                raw = source/'raw'/mode; raw.write_text(mode)
                (source/(mode+'.json')).write_text(json.dumps([{'files': {'raw/'+mode: digest(raw)}}]))
            (source/'acquisition.json').write_text('{"files": {}}')
            (source/'manifest.json').write_text('{"loaded":"loaded.json", "unloaded":"unloaded.json"}')
            for mode, other in [('loaded', 'unloaded'), ('unloaded', 'loaded')]:
                target=root/(mode+'.tar.gz'); pack_mode(source,target,mode)
                with input_file(target,mode+'.json') as records:
                    self.assertFalse((records.parent/(other+'.json')).exists())
                    self.assertFalse((records.parent/'raw'/other).exists())
                    self.assertTrue((records.parent/'raw'/mode).exists())
                    self.assertNotIn(other,json.loads((records.parent/'manifest.json').read_text()))
