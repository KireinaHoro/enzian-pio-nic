import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from cpu_isolation import Isolation, cpus, mask

class IsolationTests(unittest.TestCase):
    def test_cpu_masks(self):
        self.assertEqual(cpus('0-3,16-47'),set(range(4))|set(range(16,48)))
        self.assertEqual(mask(set(range(16,48))),'0000ffff,ffff0000')

    def test_managed_irq_activity_invalidates(self):
        obj=Isolation.__new__(Isolation);obj.guarded={'57':dict(name='nvme0q9',counts=[0]*48)}
        line='57: '+' '.join(['0']*48)+' ITS-MSI Edge nvme0q9'
        with patch.object(Path,'read_text',return_value=line):obj.verify()
        changed='57: 1 '+' '.join(['0']*47)+' ITS-MSI Edge nvme0q9'
        with patch.object(Path,'read_text',return_value=changed):
            with self.assertRaisesRegex(RuntimeError,'IRQ activity'):obj.verify()

    def test_changes_are_saved_before_write_and_restored(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);control=root/'control';control.write_text('16-47\n')
            obj=Isolation.__new__(Isolation);obj.saved=[];obj.isolated=set(range(16,48));obj.output=root/'evidence.json'
            obj.change(control,'0-15')
            self.assertIn('16-47',obj.output.read_text())
            self.assertEqual(control.read_text(),'0-15\n')
            obj.restore()
            self.assertEqual(control.read_text(),'16-47\n')

if __name__=='__main__':unittest.main()
