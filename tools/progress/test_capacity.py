import itertools
import unittest
import copy
from verify_config import verify_config
from capacity import Layout, alias, unalias, color, compatible, encoding, prefixes, frontier, verify, configuration, encode, decode


class CapacityTests(unittest.TestCase):
    def test_scramble_basis_and_lines(self):
        for i in list(range(1 << 15)) + [1 << b for b in range(31)]:
            self.assertEqual(unalias(alias(i)), i)
        # Independent transcription of the documented six-bit color equations.
        for i in range(1 << 19):
            expected = (((i >> 5 ^ i >> 18) & 1) << 5 |
                        ((i >> 3 ^ i >> 16 ^ i >> 5) & 3) << 3 |
                        (i ^ i >> 13 ^ i >> 5) & 7)
            self.assertEqual(color(i * 128, 6), expected)

    def test_kernel_partition_exhaustive(self):
        for bits, width, stride in itertools.product((2, 3, 5, 6, 7), (12, 14), (8, 10)):
            reps, basis = encoding(width, stride, bits)
            mapped = [base ^ p for base in reps.values() for p in prefixes(basis)]
            self.assertEqual(sorted(mapped), list(range(0, 1 << width, 1 << stride)))
            for m, base in reps.items():
                self.assertTrue(all(color(base ^ p, bits) == m for p in prefixes(basis)))

    def test_current_full_duplex_rejected(self):
        self.assertFalse(frontier(Layout(0, 256, 12), width=20)['frontier'])

    def test_small_search_against_brute_force(self):
        for bits in (3, 4):
            layout = Layout(0, 2, 0)
            result = frontier(layout, bits=bits, width=13, stride=9)
            reps, _ = encoding(13, 9, bits)
            maximum = -1
            for b, base in reps.items():
                bs = [layout.signature(base, d, bits) for d in (0, 1)]
                for k in range(len(reps)):
                    for ws in itertools.combinations([m for m in reps if m != b], k):
                        if all(compatible(bs + [layout.signature(reps[m], d, bits) for m, d in zip(ws, dirs)])
                               for dirs in itertools.product((0, 1), repeat=k)):
                            maximum = max(maximum, k)
            self.assertEqual(max((r['workers'] for r in result['frontier']), default=-1), maximum)
            self.assertTrue(verify(result))

    def test_new_design_round_trip_and_reservations(self):
        r = frontier(Layout(0, 2, 0), bits=4, width=15, stride=9, reserved=((0, 512),))
        self.assertTrue(verify(r))
        row = r['frontier'][-1]
        c = configuration(r, row['workers'], min(16, row['domains']))
        for w, d, off in itertools.product(range(c['worker_count']), range(c['domain_count']), (0, 128, 511)):
            self.assertEqual(decode(c, encode(c, w, d, off)), (w, d, off))
        with self.assertRaises(ValueError):
            decode(c, 1 << 15)

    def test_emitted_decoder_and_tampered_witness(self):
        r = frontier(Layout(0, 4, 2), bits=5, width=26, stride=17)
        c = configuration(r, 3, 16)
        self.assertEqual(verify_config(c), 48)
        broken = copy.deepcopy(c)
        broken['workers'][1]['base_bytes'] = broken['workers'][0]['base_bytes']
        with self.assertRaises(AssertionError):
            verify_config(broken)
        with self.assertRaises(ValueError):
            encode(c, -1, 0)
        with self.assertRaises(ValueError):
            encode(c, 0, 16)

    def test_sweep_equivalence_and_large_geometry(self):
        # Independently check the period used to exhaust 1024-CL placements.
        for line in range(1024):
            self.assertEqual(color(line * 128, 6), color((line % 256) * 128, 6))
        for rx, tx in ((6, 38), (6, 294), (250, 512)):
            layout = Layout(rx, tx, 12)
            for mask_a, mask_b in itertools.product((0, 2, 14, 30, 63), repeat=2):
                a = [layout.signature(mask_a << 20, d, 6) for d in (0, 1)]
                b = [layout.signature(mask_b << 20, d, 6) for d in (0, 1)]
                expanded = compatible(a) and compatible(b) and all(
                    compatible([x, y]) for x in a for y in b)
                self.assertEqual(compatible(a + b), expanded)
        r = frontier(Layout(), bits=7, width=27)
        self.assertEqual(r['frontier'][-1]['workers'], 15)
        self.assertTrue(verify(r))

    def test_invalid_layout(self):
        for layout in (Layout(0, 1, 1), Layout(-1, 10, 0), Layout(0, 2, -1)):
            with self.assertRaises(ValueError):
                frontier(layout)


if __name__ == '__main__':
    unittest.main()
