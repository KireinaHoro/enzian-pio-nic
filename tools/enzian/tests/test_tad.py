import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('tad', Path(__file__).resolve().parents[1] / 'assess_tad.py')
tad = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tad)


def rows():
    return ''.join(f'TAD{i} BAR={0x100000+i*0x100000:016x} INT=0000000000000000 STAT=0000000000000000 TIMEOUT=0000000000000000\n' for i in range(8))


class TadTests(unittest.TestCase):
    def test_all_units_and_contiguous_logs(self):
        kernel = 'kernel boot\nmapped L2C_TAD0 STAT=0 TIMEOUT=0\n'
        result = tad.assess(rows(), rows(), kernel, kernel+'ordinary traffic\n')
        self.assertTrue(result['passed'])
        self.assertEqual(len(result['after']), 8)

    def test_missing_or_duplicate_unit_fails(self):
        for invalid in [rows().replace('TAD7', 'TAD6'), '\n'.join(rows().splitlines()[:-1])]:
            self.assertFalse(tad.assess(rows(), invalid, 'boot\n', 'boot\n')['passed'])

    def test_interrupt_timeout_and_kernel_fault_are_independent(self):
        irq = rows().replace('INT=0000000000000000', 'INT=0000000000020000', 1)
        timeout = rows().replace('TIMEOUT=0000000000000000', 'TIMEOUT=00000000000a0080', 1)
        fault = 'boot\nEDAC DEVICE0: UE: L2C_TAD_INT: 0000000000020000 LFB entry timeout\n'
        for after, kernel in [(irq, 'boot\n'), (timeout, 'boot\n'), (rows(), fault)]:
            self.assertFalse(tad.assess(rows(), after, 'boot\n', kernel)['passed'])

    def test_log_loss_cannot_look_clean(self):
        self.assertFalse(tad.assess(rows(), rows(), 'old boot\n', 'new tail\n')['passed'])


if __name__ == '__main__':
    unittest.main()
