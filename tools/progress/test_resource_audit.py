import json
import re
from pathlib import Path
import unittest
from audit_resources import resident_line_bound
from capacity import encode, color


class ResourceAuditTests(unittest.TestCase):
    def test_all_selected_lines_can_reside(self):
        config = json.loads((Path(__file__).resolve().parents[2] /
                             'model/steady/selected-8k.json').read_text())[0]['configuration']
        # Includes both RX and TX, not only whichever direction is executing.
        self.assertEqual(resident_line_bound(config), (6, {1: 32, 6: 32}))

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
