import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sys
import subprocess

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('trace_latency', ROOT / 'tools/physical/trace_latency.py')
latency = importlib.util.module_from_spec(spec)
spec.loader.exec_module(latency)
sys.path.insert(0, str(ROOT / 'data/eci/sys_trace'))
import dma_decode


class TraceLatencyTests(unittest.TestCase):
    def template(self):
        return dict(pipeline_resolution='implementation_required', sample=dict(timestamp_width=8), sources=[
            dict(source=0, type='lauberhorn_event', pipeline_stages=None,
                 autopipeline=dict(instance='traceAuto_0', limit=10, base_stages=2, width=3))])

    def rows(self, depth):
        return [dict(source='0', instance='traceAuto_0', bit=str(bit), stages=str(depth)) for bit in range(3)]

    def test_every_depth_and_timestamp_wrap(self):
        for depth in range(2, 13):
            result = latency.resolve(self.template(), self.rows(depth), 'revision', 'hash')
            with tempfile.TemporaryDirectory() as tmp:
                p = Path(tmp) / 'map.json'; p.write_text(json.dumps(result))
                loaded = dma_decode.load_map(p)
                self.assertEqual(dma_decode.adjust_timestamp_for_source((250 + depth) % 256, 0, loaded), 250)

    def test_reject_missing_valid_misalignment_bad_depth_and_instance(self):
        for rows in [self.rows(3)[:2], self.rows(13), [], self.rows(3) + self.rows(3)[:1]]:
            with self.assertRaises(ValueError): latency.resolve(self.template(), rows, 'r', 'h')
        for field, value in [('stages', '4'), ('instance', 'other')]:
            rows = self.rows(3); rows[0][field] = value
            with self.assertRaises(ValueError): latency.resolve(self.template(), rows, 'r', 'h')

    def test_constant_inactive_source_has_no_invented_latency(self):
        row = dict(source='0', instance='traceAuto_0', bit='inactive', stages='GND')
        result = latency.resolve(self.template(), [row], 'r', 'h')
        self.assertIsNone(result['sources'][0]['pipeline_stages'])
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'map.json'; p.write_text(json.dumps(result))
            loaded = dma_decode.load_map(p)
            with self.assertRaisesRegex(ValueError, 'inactive source'):
                dma_decode.source_pipeline_stages(0, loaded)
        for rows in ([row, row], [row] + self.rows(4), self.rows(4) + [row], [dict(row, stages='VCC')]):
            with self.assertRaises(ValueError): latency.resolve(self.template(), rows, 'r', 'h')

    def test_unresolved_map_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'map.json'; p.write_text(json.dumps(self.template()))
            with self.assertRaises(ValueError): dma_decode.load_map(p)

    def test_cli_emits_matched_artifact_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / 'template.json'
            template.write_text(json.dumps(self.template()))
            evidence = root / 'depths.tsv'
            evidence.write_text('source\tinstance\tbit\tstages\n' +
                                ''.join(f'0\ttraceAuto_0\t{bit}\t4\n' for bit in range(3)))
            checkpoint = root / 'shell_lauberhorn-eci_routed.dcp'
            checkpoint.write_bytes(b'checkpoint fixture')
            bitstream = root / 'shell_lauberhorn-eci.bit'
            bitstream.write_bytes(b'bitstream fixture')
            output = root / 'map.json'
            subprocess.run([sys.executable, str(ROOT / 'tools/physical/trace_latency.py'),
                            'resolve', str(template), str(evidence), str(output),
                            'revision', str(checkpoint)], check=True)
            result = json.loads(output.read_text())
            for key, path in [('checkpoint', checkpoint), ('bitstream', bitstream),
                              ('template', template), ('depths', evidence)]:
                self.assertEqual(result['implementation'][key + '_sha256'], latency.digest(path))
            self.assertEqual(result['sources'][0]['pipeline_stages'], 4)

    def test_optimized_constant_payload_bits(self):
        result = latency.resolve(self.template(), self.rows(4)[1:], 'r', 'h')
        self.assertEqual(result['sources'][0]['pipeline_stages'], 4)


if __name__ == '__main__': unittest.main()
