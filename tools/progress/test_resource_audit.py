import json
from pathlib import Path
import unittest
from audit_resources import resident_line_bound


class ResourceAuditTests(unittest.TestCase):
    def test_all_selected_lines_can_reside(self):
        config = json.loads((Path(__file__).resolve().parents[2] /
                             'model/steady/selected-8k.json').read_text())[0]['configuration']
        # Includes both RX and TX, not only whichever direction is executing.
        self.assertEqual(resident_line_bound(config), (6, {1: 32, 6: 32}))
