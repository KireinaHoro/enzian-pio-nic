"""A shutdown crash may salvage receiver evidence, never hide a trigger error."""
import unittest
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[3]))
from tools.enzian.trace.trigger import trigger_result


class Trigger(unittest.TestCase):
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
