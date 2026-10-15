"""Exercise transport validation without using a reservation or NIC."""
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from .acquire import extract, remote_script


class Acquisition(unittest.TestCase):
    def test_remote_payload_compiles(self):
        script=remote_script('http://fixture/request','http://fixture/peer','a'*64,
                             '/tmp/image with spaces','b'*64)
        compile(script.split('\n',1)[1].rsplit('\nECHOROLE',1)[0],'<remote>','exec')

    def test_archive_keeps_only_regular_cohort_members(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for i,(name,kind,valid) in enumerate([
                    ('cohort/samples.csv',tarfile.REGTYPE,True),
                    ('cohort/../../outside',tarfile.REGTYPE,False),
                    ('other/samples.csv',tarfile.REGTYPE,False),
                    ('cohort/link',tarfile.SYMTYPE,False)]):
                archive=root/f'{i}.tar.gz'
                with tarfile.open(archive,'w:gz') as stream:
                    member=tarfile.TarInfo(name);member.type=kind
                    member.size=1 if kind==tarfile.REGTYPE else 0
                    stream.addfile(member,io.BytesIO(b'x') if member.size else None)
                if valid:
                    result=extract(archive,root/str(i),'cohort')
                    self.assertEqual((result/'samples.csv').read_bytes(),b'x')
                else:
                    with self.assertRaises(ValueError): extract(archive,root/str(i),'cohort')
                    self.assertFalse((root/str(i)).exists())


if __name__=='__main__': unittest.main()
