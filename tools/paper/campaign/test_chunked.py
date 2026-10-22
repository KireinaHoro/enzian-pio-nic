"""Chunk boundaries retain warmups, wire identities and conserved durations."""
import copy
import unittest
import tempfile
from pathlib import Path
from .chunked import plan,merge
from .import_unloaded import validate_matched,validate_request
from .wire import COMPONENTS


class Chunked(unittest.TestCase):
    def test_evidence_excludes_live_adapter_output(self):
        from .adapter import files
        from .import_loaded import digest
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'log.txt').write_text('')
            (root/'result.json').write_text('{}')
            raw=root/'observations.csv';raw.write_text('retained observations')
            saved=files(root)
            (root/'log.txt').write_text('adapter completed')
            self.assertEqual(saved,{str(raw):digest(raw)})

    def test_plan(self):
        self.assertEqual(plan(10000),[896]*11+[144])
        self.assertEqual(plan(900),[896,4])
        self.assertEqual(plan(896),[896])
        for count in (0,-1,1.5):
            with self.assertRaises(ValueError):plan(count)

    def chunk(self,count,system='DPDK',size=8,mode='off',warmup=128):
        observations=[]
        for i in range(count+warmup):
            row=dict(request_id=i+1,flow_id=0,warmup=int(i<warmup),wire_ns=1000)
            if mode=='breakdown':row['components_ns']={**dict.fromkeys(COMPONENTS,0),'cpu_wire':1000}
            observations.append(row)
        return dict(system=system,payload_bytes=size,instrumentation=mode,build_config_sha256=mode,
                    rows=observations,files={},count=count,warmup=warmup,raw_capture={'path':'fixture'},
                    paper_inputs=True,wire_available=True,methodology='tofino-wire-v1')

    def test_caladan_chunk_models_must_match(self):
        spawner=self.chunk(1,system='CS-A64')
        resident=dict(self.chunk(1,system='CS-A64'),execution_model='resident-workers')
        self.assertEqual(merge([resident])['execution_model'],'resident-workers')
        with self.assertRaisesRegex(ValueError,'execution models'):
            merge([spawner,resident])

    def test_requested_warmups_are_per_chunk(self):
        for count in (100,900,10000):
            c=merge([self.chunk(n) for n in plan(count)]) if count>896 else self.chunk(count)
            request=dict(system='DPDK',payload_bytes=8,instrumentation='off',count=count,warmup=128)
            validate_request(c,request)
            invalid=copy.deepcopy(c);invalid['warmup']+=128
            with self.assertRaises(ValueError):validate_request(invalid,request)
            if count>896:
                invalid=copy.deepcopy(c);invalid['chunks'][0]['count']-=1
                with self.assertRaises(ValueError):validate_request(invalid,request)
                invalid=copy.deepcopy(c);del invalid['chunks']
                with self.assertRaises(ValueError):validate_request(invalid,request)

    def test_merge_preserves_rearm_warmups(self):
        result=merge([self.chunk(896),self.chunk(4)])
        self.assertEqual(result['count'],900);self.assertEqual(result['warmup'],256)
        self.assertEqual(result['rows'][1024]['wire_xid'],1)
        self.assertEqual(result['rows'][1024]['warmup'],1)
        self.assertEqual(result['rows'][1152]['wire_xid'],129)
        self.assertEqual(result['rows'][1152]['warmup'],0)
        self.assertEqual(sum(not r['warmup'] for r in result['rows']),900)

    def test_accounting_version_guards(self):
        a,b=self.chunk(896),self.chunk(4)
        a['cpu_partition_version']=3; b['cpu_partition_version']=2
        with self.assertRaisesRegex(ValueError,'accounting versions'): merge([a,b])
        matrix=[merge([self.chunk(4,s,n,m)])
                for s in ('LH','DPDK') for n in (8,128,1432) for m in ('off','breakdown')]
        for c in matrix:
            if c['instrumentation']=='breakdown': c['cpu_partition_version']=2
        matrix[1]['cpu_partition_version']=3
        with self.assertRaisesRegex(ValueError,'legacy coarse'): validate_matched(matrix)

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

    def test_custom_warmup_chunk_pairing(self):
        from .import_unloaded import validate_matched
        for warmup in (0,32,256):
            chunks=plan(1500,warmup)
            cohorts=[merge([self.chunk(n,mode=mode,warmup=warmup) for n in chunks])
                     for mode in ('off','breakdown')]
            validate_matched(cohorts,systems=('DPDK',),sizes=(8,))
            for c in cohorts:
                self.assertEqual(c['warmup'],warmup*len(chunks))
                validate_request(c,dict(system='DPDK',payload_bytes=8,
                    instrumentation=c['instrumentation'],count=1500,warmup=warmup))

if __name__=='__main__':unittest.main()
