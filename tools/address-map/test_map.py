#!/usr/bin/env python3
"""Check generated C against the independent capacity model and bad addresses."""
import ctypes
import json
import random
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/progress'))
import capacity
import generate


class AddressMapTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        path = Path(cls.tmp.name)
        (path / 'test.c').write_text('''
#include <stdint.h>
#include "fixed_worker_map.h"
int encode(unsigned n, unsigned w, unsigned d, unsigned o, uint64_t *a)
{ return lh_map_encode(n, w, d, o, a); }
int decode(unsigned n, uint64_t a, unsigned *w, unsigned *d, unsigned *o)
{ return lh_map_decode(n, a, w, d, o); }
int exhaustive(void) {
    unsigned w, d, rw, rd, ro;
    uint64_t a;
    for (w = 0; w < LH_MAP_MAX_WORKERS; ++w)
        for (d = 0; d < LH_MAP_DOMAIN_LIMIT; ++d) {
            if (lh_map_encode(7, w, d, 8191, &a) ||
                lh_map_decode(7, a, &rw, &rd, &ro) ||
                rw != w || rd != d || ro != 8191) return -1;
        }
    return 0;
}
''')
        subprocess.run(['cc', '-O2', '-std=c11', '-Wall', '-Wextra', '-Werror',
                        '-shared', '-fPIC', '-I', str(ROOT / 'sw/include'),
                        str(path / 'test.c'), '-o', str(path / 'test.so')], check=True)
        cls.lib = ctypes.CDLL(str(path / 'test.so'))
        u = ctypes.c_uint
        cls.lib.encode.argtypes = [u, u, u, u, ctypes.POINTER(ctypes.c_uint64)]
        cls.lib.decode.argtypes = [u, ctypes.c_uint64] + [ctypes.POINTER(u)] * 3
        cls.config = json.loads((ROOT / 'model/data/selected-8k.json').read_text())[0]['configuration']
        # Scheduler's 16 active slots do not constrain the PA lifetime namespace.
        cls.config['domain_count'] = 1 << 19

    def encode(self, workers, worker, domain, offset):
        a = ctypes.c_uint64(0xdeadbeef)
        ret = self.lib.encode(workers, worker, domain, offset, ctypes.byref(a))
        return ret, a.value

    def decode(self, workers, address):
        out = [ctypes.c_uint(0xdeadbeef) for _ in range(3)]
        ret = self.lib.decode(workers, address, *[ctypes.byref(x) for x in out])
        return ret, tuple(x.value for x in out)

    def test_generated_collateral(self):
        for path, text in generate.render().items():
            self.assertEqual((ROOT / path).read_text(), text, path)

    def test_every_domain_round_trip(self):
        self.assertEqual(self.lib.exhaustive(), 0)

    def test_independent_capacity_oracle(self):
        rng = random.Random(0x8c00)
        domains = [0, 1, 15, 16, (1 << 19) - 1] + [1 << i for i in range(19)]
        domains += [rng.randrange(1 << 19) for _ in range(100)]
        offsets = [0, 8191]
        for direction in self.config['directions'].values():
            offsets += direction['control_offsets_bytes'] + direction['overflow_offsets_bytes']
        for n in range(1, 8):
            for w in range(n):
                for d in domains:
                    for off in offsets:
                        expected = capacity.encode(self.config, w, d, off)
                        self.assertEqual(self.encode(n, w, d, off), (0, expected))
                        self.assertEqual(self.decode(n, expected), (0, capacity.decode(self.config, expected)))

    def test_reject_without_truncation_or_partial_result(self):
        for args in [(0, 0, 0, 0), (8, 0, 0, 0), (4, 4, 0, 0),
                     (4, 0, 1 << 19, 0), (4, 0, 0, 8192)]:
            self.assertEqual(self.encode(*args), (-1, 0xdeadbeef))
        for n, address in [(0, 0), (8, 0), (7, 0), (7, 1 << 38),
                           (7, (1 << 64) - 1),
                           (4, capacity.encode(self.config, 4, 1)), (7, 8192)]:
            self.assertEqual(self.decode(n, address), (-1, (0xdeadbeef,) * 3))

    def test_arbitrary_addresses_against_oracle(self):
        rng = random.Random(19)
        for _ in range(10000):
            address = rng.randrange(1 << 40)
            n = rng.randrange(1, 8)
            try:
                expected = capacity.decode(self.config, address)
                valid = expected[0] < n
            except ValueError:
                valid = False
            actual = self.decode(n, address)
            if valid:
                self.assertEqual(actual, (0, expected))
            else:
                self.assertEqual(actual, (-1, (0xdeadbeef,) * 3))


if __name__ == '__main__':
    unittest.main()
