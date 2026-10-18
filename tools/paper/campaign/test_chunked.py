"""Chunk boundaries retain warmups, wire identities and conserved durations."""
import copy
import unittest
from .chunked import plan,merge
from .import_unloaded import validate_matched
from .wire import COMPONENTS


class Chunked(unittest.TestCase):
    def test_plan(self):
        self.assertEqual(plan(10000),[896]*11+[144])
        self.assertEqual(plan(900),[896,4])
        self.assertEqual(plan(896),[896])
        for count in (0,-1,1.5):
            with self.assertRaises(ValueError):plan(count)

    def chunk(self,count,system='DPDK',size=8,mode='off'):
        observations=[]
        for i in range(count+128):
            row=dict(request_id=i+1,flow_id=0,warmup=int(i<128),wire_ns=1000)
            if mode=='breakdown':row['components_ns']={**dict.fromkeys(COMPONENTS,0),'cpu_wire':1000}
            observations.append(row)
        return dict(system=system,payload_bytes=size,instrumentation=mode,build_config_sha256=mode,
                    rows=observations,files={},count=count,warmup=128,raw_capture={'path':'fixture'},
                    paper_inputs=True,wire_available=True,methodology='tofino-wire-v1')

    def test_merge_preserves_rearm_warmups(self):
        result=merge([self.chunk(896),self.chunk(4)])
        self.assertEqual(result['count'],900);self.assertEqual(result['warmup'],256)
        self.assertEqual(result['rows'][1024]['wire_xid'],1)
        self.assertEqual(result['rows'][1024]['warmup'],1)
        self.assertEqual(result['rows'][1152]['wire_xid'],129)
        self.assertEqual(result['rows'][1152]['warmup'],0)
        self.assertEqual(sum(not r['warmup'] for r in result['rows']),900)

    def test_matrix_checks_chunk_identity_and_counts(self):
        matrix=[merge([self.chunk(896,s,n,m),self.chunk(4,s,n,m)])
                for s in ('LH','DPDK') for n in (8,128,1432) for m in ('off','breakdown')]
        validate_matched(matrix)
        for key,value in [('wire_xid',9),('chunk',7),('warmup',0)]:
            invalid=copy.deepcopy(matrix);invalid[0]['rows'][1024][key]=value
            with self.assertRaises(ValueError):validate_matched(invalid)
        invalid=copy.deepcopy(matrix);invalid[0]['chunks'][1]['count']=5
        with self.assertRaises(ValueError):validate_matched(invalid)

    def test_merge_rejects_mixed_builds_and_missing_rows(self):
        a,b=self.chunk(896),self.chunk(4)
        b['build_config_sha256']='different'
        with self.assertRaises(ValueError):merge([a,b])
        b=self.chunk(4);b['rows'].pop()
        with self.assertRaises(ValueError):merge([a,b])
        b=self.chunk(4);b['rows'][0]['request_id']=2
        with self.assertRaises(ValueError):merge([a,b])

if __name__=='__main__':unittest.main()
