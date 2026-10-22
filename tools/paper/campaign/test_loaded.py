"""Loaded deadline-loss population is independent of arrival regularity."""
import unittest
from .policy import loss

class Loaded(unittest.TestCase):
    def test_losses_include_late_and_rejections(self):
        t=dict(transmitted=103,on_time=100,late=1,duplicates=2,generator_misses=0,
            tx_admission_failures=0,malformed=0,server_rejections=2,duration_ns=1000000000,
            deadline_ns=10000000,valid=True)
        self.assertAlmostEqual(loss(t),3/103)
        t['generator_misses']=1
        with self.assertRaises(ValueError):loss(t)
