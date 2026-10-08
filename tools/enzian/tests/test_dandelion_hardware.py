"""Clock fitting must preserve causal bounds rather than assume RX latency."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from dandelion_hardware import clock_fit


class ClockTests(unittest.TestCase):
    def test_offset_and_drift_with_asymmetric_bounds(self):
        bounds = []
        truth = []
        for i in range(300):
            hw = 100_000_000_000 + i * 100_000_000
            sw = hw + 17_000_000_000 + (hw - 100_000_000_000) * 12e-6
            # Alternating tight upper/lower observations constrain both sides.
            lo, hi = ((sw - 80, sw + 600) if i % 2 else (sw - 700, sw + 90))
            bounds.append((hw, lo, hi))
            truth.append((hw, sw))
        model, convert = clock_fit(bounds)
        self.assertLess(model['uncertainty_ns'], 400)
        for hw, sw in truth:
            self.assertLessEqual(abs(convert(hw) - sw), model['uncertainty_ns'])
        for hw, lo, hi in bounds:
            self.assertGreaterEqual(convert(hw), lo - model['slack_ns'])
            self.assertLessEqual(convert(hw), hi + model['slack_ns'])

    def test_inconsistent_correspondence_rejected(self):
        with self.assertRaisesRegex(ValueError, 'slack'):
            clock_fit([(100, 1000, 1100), (100, 10000, 10100)])


if __name__ == '__main__':
    unittest.main()
