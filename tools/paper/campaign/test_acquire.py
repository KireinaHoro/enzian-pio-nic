"""Exercise transport validation without using a reservation or NIC."""
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from .acquire import extract, remote_script


class Acquisition(unittest.TestCase):
    def test_timing_retry_rejects_other_failures_and_changed_seed(self):
        import json
        from .acquire import validate_timing_failure
        request=dict(role='client',mode='loaded',seed=1,slot_ns=1000,rate=350000,duration_ms=10000,warmup_ms=2000)
        summary=dict(valid=False,generator_misses=2,tx_admission_failures=0,malformed=0,
                     nic_rx_no_buffer=0,nic_rx_missed=0,nic_rx_errors=0,nic_tx_errors=0,
                     post_observation_nic_rx_missed=0,rx_observation=[],sender_period_ns=8000,
                     arrival_model='slotted-poisson',seed=1,slot_ns=1000,rate=350000,
                     duration_ns=10000000000,warmup_ns=2000000000)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'runner').mkdir()
            log=root/'runner/client.log'
            log.write_text('load_error=TX slot exceeded sender period; no catch-up\n')
            path=root/'samples.csv.open.json';path.write_text(json.dumps(summary))
            validate_timing_failure(root,request)
            for key,value in [('seed',2),('nic_rx_errors',1),('tx_admission_failures',1),('valid',True)]:
                with self.subTest(key=key):
                    path.write_text(json.dumps(dict(summary,**{key:value})))
                    with self.assertRaises(ValueError): validate_timing_failure(root,request)
            path.write_text(json.dumps(summary))
            log.write_text('load_error=RX queue cannot drain within sender period; RTT biased\n')
            with self.assertRaises(ValueError): validate_timing_failure(root,request)

    def test_upload_roundtrip_and_existing_archive_preservation(self):
        import hashlib,json
        from unittest.mock import patch
        from .peer import upload
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'cohort';root.mkdir()
            data=b'request,latency\n1,123\n'*10000
            (root/'samples.csv').write_bytes(data)
            with patch('urllib.request.urlopen') as remote:
                upload(root,'http://fixture')
                self.assertEqual(remote.call_count,1)
            saved=(root.with_suffix('.tar.gz')).read_bytes()
            extracted=extract(root.with_suffix('.tar.gz'),Path(directory)/'extracted','cohort')
            self.assertEqual((extracted/'samples.csv').read_bytes(),data)
            self.assertEqual(json.loads((extracted/'files.json').read_text())['samples.csv'],hashlib.sha256(data).hexdigest())
            with self.assertRaises(FileExistsError):upload(root,'http://fixture')
            self.assertEqual(root.with_suffix('.tar.gz').read_bytes(),saved)

    def test_remote_payload_compiles(self):
        script=remote_script('http://fixture/request','http://fixture/peer','a'*64,
                             '/tmp/image with spaces','b'*64)
        compile(script.split('\n',1)[1].rsplit('\nLHIMAGE',1)[0],'<remote>','exec')

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
