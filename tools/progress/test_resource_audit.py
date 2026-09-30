import json
import re
from pathlib import Path
import unittest
from audit_resources import resident_line_bound, retained_set_pressure
from capacity import encode, color, alias


class ResourceAuditTests(unittest.TestCase):
    def test_active_assignment_footprint_only(self):
        config = json.loads((Path(__file__).resolve().parents[2] /
                             'model/steady/selected-8k.json').read_text())[0]['configuration']
        # Includes both RX and TX, not only whichever direction is executing.
        self.assertEqual(resident_line_bound(config), (6, {1: 32, 6: 32}))

    def test_retained_pressure_matches_enumeration(self):
        from collections import Counter
        root = Path(__file__).resolve().parents[2]
        original = json.loads((root/'model/steady/selected-8k.json').read_text())[0]['configuration']
        for domains in (1, 3, 16):
            config = json.loads(json.dumps(original))
            config['domain_count'] = domains
            offsets = {o for d in config['directions'].values()
                       for field in ('control_offsets_bytes', 'overflow_offsets_bytes') for o in d[field]}
            counts = Counter(alias(encode(config,w,d,o) >> 7) & 8191
                             for w in range(config['worker_count'])
                             for d in range(domains) for o in offsets)
            counts.update(alias((config['bypass']['base_bytes']+o) >> 7) & 8191 for o in offsets)
            self.assertEqual(retained_set_pressure(config),
                             (max(counts.values()),dict(sorted(Counter(counts.values()).items()))))
        config['domain_count'] = 1 << len(config['domain_encoding']['kernel_basis_bytes'])
        self.assertGreater(retained_set_pressure(config)[0], 16)

    def test_late_unlock_uses_selected_addresses(self):
        root = Path(__file__).resolve().parents[2]
        config = json.loads((root/'model/steady/selected-8k.json').read_text())[0]['configuration']
        model = (root/'model/steady/LateUnlock.tla').read_text()
        for name, worker, direction in [('OldTX', 0, 'tx'), ('StalledRX', 0, 'rx'), ('PeerRX', 1, 'rx')]:
            address = int(re.search(name + r' == "(0x[0-9a-f]+)"', model).group(1), 16)
            offset = config['directions'][direction]['control_offsets_bytes'][0]
            self.assertEqual(address, encode(config, worker, 0, offset))
            declared_color = int(re.search('a = ' + name + r' -> (\d+)', model).group(1))
            self.assertEqual(declared_color, color(address, config['color_bits']))
