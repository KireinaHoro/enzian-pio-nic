"""A shutdown crash may salvage receiver evidence, never hide a trigger error."""
import unittest
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[3]))
from tools.enzian.trace.trigger import trigger_result


class Trigger(unittest.TestCase):
    def test_frozen_identity_allows_only_sticky_loss_assertion(self):
        from tools.enzian.trace.session import frozen_matches
        before=dict(trace_write_slot_1=100,trace_wrapped_1=0,trace_sample_lost_1=0,trace_dma_error_1=0)
        current=dict(trace_stop=1,_status=dict(before,trace_sample_lost_1=1))
        self.assertTrue(frozen_matches(before,current))
        for key in ('trace_write_slot_1','trace_wrapped_1','trace_dma_error_1'):
            self.assertFalse(frozen_matches(before,dict(current,_status=dict(current['_status'],**{key:before[key]+1}))))
        self.assertFalse(frozen_matches(before,dict(current,trace_stop=0)))
        self.assertFalse(frozen_matches(dict(before,trace_sample_lost_1=1),dict(trace_stop=1,_status=before)))

    def test_freeze_shutdown_requires_clean_completed_control(self):
        from tools.enzian.trace.control import freeze_shutdown_crash
        log='\n'.join(('DIAG vio_output design/trace_stop 1',
            'Closing hw_target cable','# exit 0','Exiting Vivado at Thursday','Segmentation fault'))
        self.assertTrue(freeze_shutdown_crash(139,log))
        for bad in (log.replace('trace_stop 1','trace_stop 0'),log.replace('# exit 0',''),
                    log.replace('Exiting Vivado at Thursday',''),log+'\nERROR: failure'):
            self.assertFalse(freeze_shutdown_crash(139,bad))
        self.assertFalse(freeze_shutdown_crash(1,log))

    def test_freeze_recovery_requires_independent_successful_readback(self):
        import tempfile
        from unittest.mock import patch
        from types import SimpleNamespace
        from tools.enzian.trace.control import Control
        original='\n'.join(('DIAG vio_output design/trace_stop 1',
            'Closing hw_target cable','# exit 0','Exiting Vivado at Thursday'))
        for returncode,stop,passes in ((0,1,True),(0,0,False),(139,1,False)):
            with tempfile.TemporaryDirectory() as directory:
                control=object.__new__(Control);control.directory=Path(directory)
                control.remote='/fixture';control.setup=lambda:None;control.command=lambda *a:None
                control.config=dict(vivado='vivado',hw_server='server',cable='cable',ltx='map',
                    peer_ip='192.0.2.1',peer_mac='00:00:00:00:00:01',programmer='fixture')
                calls=[]
                def run(argv,stdout,**kwargs):
                    calls.append(argv)
                    stdout.write(original if len(calls)==1 else original.replace('trace_stop 1',f'trace_stop {stop}'))
                    return SimpleNamespace(returncode=139 if len(calls)==1 else returncode)
                with patch('tools.enzian.trace.control.subprocess.run',side_effect=run):
                    if passes:self.assertEqual(control._call('freeze')['trace_stop'],1)
                    else:
                        with self.assertRaises(RuntimeError):control._call('freeze')
                self.assertEqual(len(calls),2)
                self.assertIn('status',calls[1][-1])
                self.assertEqual((Path(directory)/'freeze-shutdown-recovery.json').exists(),passes)

    def test_status_shutdown_retry_is_bounded(self):
        import tempfile
        from unittest.mock import patch
        from types import SimpleNamespace
        from tools.enzian.trace.control import Control
        original='\n'.join(('DIAG vio_output design/trace_stop 1',
            'Closing hw_target cable','# exit 0','Exiting Vivado at Thursday'))
        for returncode,stop,passes in ((0,1,True),(139,1,False),(1,1,False)):
            with tempfile.TemporaryDirectory() as directory:
                control=object.__new__(Control);control.directory=Path(directory)
                control.remote='/fixture';control.setup=lambda:None;control.command=lambda *a:None
                control.config=dict(vivado='vivado',hw_server='server',cable='cable',ltx='map',
                    peer_ip='192.0.2.1',peer_mac='00:00:00:00:00:01',programmer='fixture')
                calls=[]
                def run(argv,stdout,**kwargs):
                    calls.append(argv)
                    stdout.write(original if len(calls)==1 else original.replace('trace_stop 1',f'trace_stop {stop}'))
                    return SimpleNamespace(returncode=139 if len(calls)==1 else returncode)
                with patch('tools.enzian.trace.control.subprocess.run',side_effect=run):
                    if passes:self.assertEqual(control._call('status')['trace_stop'],1)
                    else:
                        with self.assertRaises(RuntimeError):control._call('status')
                self.assertEqual(len(calls),2)
                self.assertIn('status',calls[1][-1])
                self.assertEqual((Path(directory)/'status-shutdown-recovery.json').exists(),passes)

    def test_shutdown_crash_requires_completed_trigger_and_frozen_recorder(self):
        log='\n'.join(('UDP_DUMP_TRIGGERED','TRACE_STATUS trace_dma_error_1 0',
            'DIAG vio_output design/trace_stop 1','Closing hw_target cable',
            'Exiting Vivado at Thursday','Segmentation fault'))
        self.assertTrue(trigger_result(139,log)['shutdown_crash'])
        self.assertFalse(trigger_result(0,log)['shutdown_crash'])
        for bad in (log.replace('UDP_DUMP_TRIGGERED',''),log.replace('trace_stop 1','trace_stop 0'),
                    log.replace('trace_dma_error_1 0','trace_dma_error_1 1'),
                    log.replace('Exiting Vivado at Thursday',''),log+'\nERROR: trigger failed'):
            with self.assertRaises(RuntimeError):trigger_result(139,bad)
        with self.assertRaises(RuntimeError):trigger_result(1,log)


if __name__=='__main__':unittest.main()
