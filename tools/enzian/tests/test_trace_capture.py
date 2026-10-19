"""Canonical capture lifecycle, preservation and archive trust boundaries."""
import io
import subprocess
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import Mock,patch
import contextlib
sys.path.insert(0,str(Path(__file__).resolve().parents[3]))
from tools.enzian.trace import session
from tools.enzian.trace.transport import extract


class Capture(unittest.TestCase):
    def run_capture(self, failure=None,restore_failure=False):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'capture';control=Mock();transport=Mock()
            control.exclusive.side_effect=contextlib.nullcontext
            transport.remote="/fixture/receiver"
            end=dict(trace_write_slot_1=64,trace_wrapped_1=0,trace_sample_lost_1=0,trace_dma_error_1=0)
            def call(mode,output=None):
                if mode=='configure':
                    Path(output).write_text('BEFORE_FREEZE trace_sample_lost_1 0\n')
                if mode=='preserve' and restore_failure:raise RuntimeError('restore failed')
                return dict(trace_stop=1,_status=end)
            control.call.side_effect=call;control.remote='/fixture'
            control.check.return_value=dict(head_matches=True,tail_matches=True)
            transport.receive.side_effect=failure
            def export(size):
                raw=root/'raw.bin';raw.write_bytes(b'x'*size);return raw
            transport.export.side_effect=export
            with patch.object(session,'Control',return_value=control),patch.object(session,'Transport',return_value=transport),patch.object(session.subprocess,'run') as run:
                run.return_value.returncode=0
                if failure or restore_failure:
                    with self.assertRaisesRegex(RuntimeError,str(failure) if failure else 'restore failed'):
                        session.capture(dict(programmer='fixture'),root)
                else:
                    result=session.capture(dict(programmer='fixture'),root)
                    self.assertTrue(result['passed']);self.assertEqual(result['bytes'],4096)
                self.assertEqual(control.call.call_args_list[-1].args[0],'preserve')
                state=json.loads((root/'capture.json').read_text())
                if failure:
                    control.check.assert_not_called() # No JTAG fallback on Ethernet failure.
                    self.assertEqual(state['error'],str(failure))
                if restore_failure:self.assertEqual(state['restore_error'],'restore failed')
                self.assertTrue((root/'manifest.json').exists())
                with self.assertRaises(FileExistsError):session.capture({},root)

    def test_cable_selector_normalizes_vendor_once(self):
        source=Path(session.__file__).with_name('control.tcl')
        for cable in ('ABC123','Digilent/ABC123'):
            script='\n'.join([
                'proc open_hw_manager {} {}',
                'proc connect_hw_server {args} {}',
                'proc get_hw_targets {pattern} {puts "SELECTOR=$pattern"; error "fixture stop"}',
                'set argv {status server '+cable+'}',
                'source {'+str(source)+'}'])
            result=subprocess.run(['tclsh'],input=script,text=True,capture_output=True)
            self.assertEqual(result.returncode,1)
            self.assertIn('SELECTOR=*/xilinx_tcf/Digilent/ABC123',result.stdout)
            self.assertNotIn('Digilent/Digilent/',result.stdout)

    def test_success(self):self.run_capture()
    def test_ethernet_failure_preserves_and_restores(self):self.run_capture(RuntimeError('receiver failed'))
    def test_cleanup_does_not_mask_primary_failure(self):self.run_capture(RuntimeError('receiver failed'),True)
    def test_restore_failure_is_terminal(self):self.run_capture(restore_failure=True)

    def test_archive_rejects_unsafe_or_duplicate_members(self):
        for names in (['../escape'],['trace-dram.bin','trace-dram.bin'],['unexpected']):
            data=io.BytesIO()
            with tarfile.open(fileobj=data,mode='w') as tar:
                for name in names:
                    e=tarfile.TarInfo(name);e.size=1;tar.addfile(e,io.BytesIO(b'x'))
            data.seek(0)
            with tempfile.TemporaryDirectory() as d,self.assertRaises(ValueError):extract(data,Path(d),4096)

    def test_incomplete_receiver_cannot_be_promoted(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'capture.json').write_text('{"receiver_complete":false}')
            with self.assertRaisesRegex(ValueError,'completed receiver'):session.recover_export({},p)

    def test_export_recovery_never_retriggers_receiver(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);end=dict(trace_write_slot_1=64,trace_sample_lost_1=0,
                                  trace_wrapped_1=0,trace_dma_error_1=0)
            (root/'capture.json').write_text(json.dumps(dict(stage='failed',failed_stage='exporting',receiver_complete=True,bytes=4096,end=end)))
            control=Mock();control.remote='/fixture';control.exclusive.side_effect=contextlib.nullcontext
            control.call.return_value=dict(trace_stop=1,_status=end)
            control.check.return_value=dict(head_matches=True,tail_matches=True)
            transport=Mock();transport.remote='/fixture/receiver'
            raw=root/'raw.bin';raw.write_bytes(b'x'*4096);transport.export.return_value=raw
            with patch.object(session,'Control',return_value=control),patch.object(session,'Transport',return_value=transport),patch.object(session.subprocess,'run') as run:
                run.return_value.returncode=0
                result=session.recover_export(dict(programmer='fixture'),root)
            self.assertTrue(result['passed'])
            transport.receive.assert_not_called()
            self.assertNotIn('configure',[c.args[0] for c in control.call.call_args_list])
