"""Revision transitions retain evidence and selectively reuse acquisitions."""
import json
from pathlib import Path
import tempfile
import unittest
from .test_campaign import controller
from .recovery import draft, prepare, invalidated, inherit_unloaded
from .import_loaded import digest


class Recovery(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.source=self.root/'adapter.py';self.source.write_text('old')
        self.old={str(self.source):digest(self.source)}
        controller.atomic(self.root/'code.json',self.old)
        self.receipts=[]
        for stage in ('unloaded','rehearsal-validation'):
            token=controller.hashlib.sha256(json.dumps([stage,{}],sort_keys=True).encode()).hexdigest()[:20]
            d=self.root/'stages'/token;a=d/'attempt-1';a.mkdir(parents=True)
            controller.atomic(a/'request.json',dict(stage=stage))
            controller.atomic(a/'result.json',dict(passed=True))
            controller.atomic(d/'receipt.json',dict(result=dict(passed=True),hashes={str(p):digest(p) for p in a.iterdir()}))
            self.receipts.append(d/'receipt.json')
        self.source.write_text('fixed');self.new={str(self.source):digest(self.source)}
        self.review=draft(self.root,self.new);self.review.update(reason='Analysis fix; prior results independently revalidated',evidence={str(self.source):digest(self.source)})
        for d in self.review['receipts'].values():d.update(action='reuse',reason='Validated fixture')

    def test_inheritance_rejects_running_source(self):
        controller.atomic(self.root/'status.json',dict(stage='loaded'))
        with self.assertRaisesRegex(ValueError,'must be stopped'):
            inherit_unloaded(self.root,self.root/'new','review',controller.atomic)
        self.assertFalse((self.root/'new').exists())

    def test_selective_reuse_and_derived_invalidation(self):
        original=self.receipts[0].read_bytes()
        prepare(self.root,self.new,self.review,controller.atomic)
        self.assertFalse(invalidated(self.root,self.receipts[0]))
        self.assertTrue(invalidated(self.root,self.receipts[1]))
        c=controller.Campaign(self.root,{})
        self.assertEqual(c.stage('unloaded',{}),dict(passed=True)) # No adapter invocation.
        self.assertEqual(self.receipts[0].read_bytes(),original)
        archive=next((self.root/'recoveries').glob('*/receipts.json'))
        self.assertEqual(len(controller.read(archive)),2)
        self.assertEqual(controller.read(self.root/'code.json'),self.new)
        prepare(self.root,self.new,None,controller.atomic) # Retry launch without replaying transition.

    def test_explicit_rerun_retains_attempt(self):
        self.review['receipts'][str(self.receipts[0].relative_to(self.root))]['action']='rerun'
        prepare(self.root,self.new,self.review,controller.atomic)
        self.assertTrue(invalidated(self.root,self.receipts[0]))
        self.assertTrue((self.receipts[0].parent/'attempt-1/result.json').exists())

    def test_unreviewed_stale_and_corrupt_rejected(self):
        with self.assertRaisesRegex(ValueError,'recovery-review'):
            prepare(self.root,self.new,None,controller.atomic)
        self.review['new_code']={}
        with self.assertRaisesRegex(ValueError,'old/new'):
            prepare(self.root,self.new,self.review,controller.atomic)
        self.review['new_code']=self.new
        self.review['receipts'].pop(str(self.receipts[0].relative_to(self.root)))
        with self.assertRaisesRegex(ValueError,'every completed'):
            prepare(self.root,self.new,self.review,controller.atomic)
        self.review=draft(self.root,self.new);self.review.update(reason='fix',evidence={str(self.source):digest(self.source)})
        for d in self.review['receipts'].values():d['reason']='fixture'
        (self.receipts[0].parent/'attempt-1/result.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'evidence changed'):
            prepare(self.root,self.new,self.review,controller.atomic)
        self.assertEqual(controller.read(self.root/'code.json'),self.old)

    def test_artifact_manifest_change_rejected(self):
        manifest=self.root/'hardware.json';manifest.write_text('{}')
        new=dict(self.new,**{str(manifest):digest(manifest)})
        self.review['new_code']=new
        with self.assertRaisesRegex(ValueError,'manifests/artifacts'):
            prepare(self.root,new,self.review,controller.atomic)

    def test_failed_case_retried_without_reacquiring_completed_case(self):
        self.source.write_text("raise SystemExit(7)\n")
        controller.atomic(self.root/'code.json',{str(self.source):digest(self.source)})
        config=dict(adapter=[controller.sys.executable,str(self.source)],deadlines={'build':5})
        c=controller.Campaign(self.root,config)
        with self.assertRaises(RuntimeError): c.stage('build',{})
        self.source.write_text("import json,hashlib,sys\nfrom pathlib import Path\np=Path(sys.argv[2]);Path(sys.argv[3]).write_text(json.dumps(dict(passed=True,files={str(p):hashlib.sha256(p.read_bytes()).hexdigest()})))\n")
        code={str(self.source):digest(self.source)}
        review=draft(self.root,code)
        review.update(reason='Adapter repair preserves completed acquisitions',evidence=code)
        for d in review['receipts'].values():d.update(action='reuse',reason='Unaffected fixture')
        prepare(self.root,code,review,controller.atomic)
        c=controller.Campaign(self.root,config)
        self.assertTrue(c.stage('unloaded',{})['passed'])
        self.assertTrue(c.stage('build',{})['passed'])
        self.assertEqual(len(list(self.receipts[0].parent.glob('attempt-*'))),1)
        build=[p for p in (self.root/'stages').glob('*/attempt-2/request.json')]
        self.assertEqual(len(build),1)
        self.assertEqual(controller.read(build[0])['stage'],'build')
        self.assertTrue((build[0].parent.parent/'attempt-1/log.txt').exists())
