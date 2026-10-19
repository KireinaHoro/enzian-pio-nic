"""Local fixtures exercise selection, durable stages, and notification delivery."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from .policy import validate_activity, validate_activity_coverage, aggregate, candidates, grid, qualifying, validate_trial

SPEC=importlib.util.spec_from_file_location('controller',Path(__file__).parents[1]/'benchmark_campaign.py')
controller=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(controller)


def trial(on_time=100, transmitted=100):
    return dict(transmitted=transmitted,on_time=on_time,late=0,duplicates=0,
        generator_misses=0,tx_admission_failures=0,malformed=0,duration_ns=10_000_000_000,
        deadline_ns=10_000_000,valid=True)


class Policy(unittest.TestCase):
    def test_source_edit_does_not_invalidate_saved_stage(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            source=root/'plot.py';source.write_text('changed')
            controller.atomic(root/'code.json',{str(source):'old-source-hash'})
            request={'example':1}
            token=hashlib.sha256(json.dumps(['example',request],sort_keys=True).encode()).hexdigest()[:20]
            target=root/'stages'/token;target.mkdir(parents=True)
            controller.atomic(target/'receipt.json',dict(result={'passed':True},hashes={}))
            self.assertEqual(controller.Campaign(root,{}).stage('example',request),{'passed':True})

    def test_unloaded_scope_never_starts_loaded_collection(self):
        with tempfile.TemporaryDirectory() as directory:
            campaign=controller.Campaign(Path(directory),dict(scope='unloaded-rehearsal'))
            requests=[]
            def stage(name, request):
                requests.append((name,request))
                if name=='unloaded':return {'cohort':request}
                if name=='rehearsal-validation':return {'previews':['cdf','breakdown'],'files':{'cdf':'hash','breakdown':'hash'}}
                return {}
            campaign.stage=stage
            with patch.object(controller,'validate_matched'):
                campaign.run()
            cohorts=[r for n,r in requests if n=='unloaded']
            self.assertEqual(len(cohorts),12)
            self.assertTrue(all(r['warmup']==128 and r['count']==100 for r in cohorts))
            self.assertEqual(cohorts[0]['system'],'DPDK')
            self.assertFalse(any(n in ('loaded','finalize') for n,_ in requests))
            state=controller.read(Path(directory)/'status.json')
            self.assertEqual(state['stage'],'rehearsal-complete')
            self.assertFalse(state['full_collection_started'])

    def test_prefix_and_nonmonotonic(self):
        points=[dict(rate=r,trials=[trial(n,t)]*3) for r,n,t in [(10,100,100),(20,90,90),(30,197,200),(40,300,300)]]
        self.assertEqual([p['rate'] for p in qualifying(points)],[10,20])
        self.assertEqual([p['rate'] for p in candidates(points)],[10,20])
        self.assertEqual(len(points),4) # Overload is retained.
        self.assertEqual(aggregate(points[1]['trials'])['goodput'],9)
        points[1]['trials']=[trial(100),trial(98),trial(100)]
        self.assertEqual(len(qualifying(points)),1)

    def test_threshold_and_generator(self):
        self.assertEqual(len(qualifying([dict(rate=10,trials=[trial(99)]*3)])),1)
        t=trial(); t['generator_misses']=1
        with self.assertRaises(ValueError): validate_trial(t)
        self.assertEqual(max(grid(101)),101)
        self.assertEqual(len(grid(10000)),9)
        t=trial(); t['duration_ns']=20_000_000_000
        with self.assertRaises(ValueError): aggregate([trial(),t])

    def test_idle_workers_and_grid_coverage(self):
        validate_activity([100,0,0,0],4)
        validate_activity_coverage({('LH',8,4):[[100,0,0,0],[10,20,30,40]]})
        with self.assertRaisesRegex(ValueError,'never exercised'):
            validate_activity_coverage({('LH',8,4):[[100,0,0,0]]})
        for counts in ([0,0,0,0],[1,-1,0,0],[1]):
            with self.assertRaises(ValueError):validate_activity(counts,4)

    def test_server_rejections_count_toward_cutoff(self):
        at_limit=dict(trial(99),server_rejections=1)
        over_limit=dict(trial(98),server_rejections=2)
        points=[dict(rate=10,trials=[at_limit]*3),dict(rate=20,trials=[over_limit]*3)]
        self.assertEqual([p['rate'] for p in qualifying(points)],[10])
        self.assertEqual(aggregate([over_limit]*3)['goodput'],9.8)

    def test_resume_and_failures(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); adapter=root/'adapter.py'
            adapter.write_text('''import sys,json,hashlib
from pathlib import Path
stage,request,result=sys.argv[1:]
p=Path(request)
if json.loads(p.read_text()).get('fail'): raise SystemExit(7)
Path(result).write_text(json.dumps(dict(passed=True,files={str(p):hashlib.sha256(p.read_bytes()).hexdigest()})))
''')
            c=controller.Campaign(root,dict(adapter=[controller.sys.executable,str(adapter)],deadlines={'build':5}))
            result=c.stage('build',{})
            self.assertEqual(c.stage('build',{}),result)
            self.assertEqual(len(list((root/'stages').glob('*/attempt-*'))),1)
            with self.assertRaises(RuntimeError): c.stage('build',{'fail':True})
            path=Path(next(iter(result['files']))); path.write_text('changed')
            with self.assertRaises(ValueError): c.stage('build',{})

    def test_instrumented_cohort_separation(self):
        from .import_unloaded import validate_matched
        from .wire import COMPONENTS, METHOD
        cohorts=[]
        for system in ('LH','DPDK'):
            for size in controller.SIZES:
                for mode in ('off','breakdown'):
                    row=dict(request_id=1,warmup=0,wire_ns=100)
                    if mode=='breakdown': row['components_ns']=dict(dict.fromkeys(COMPONENTS,10),cpu_wire=20)
                    cohorts.append(dict(system=system,payload_bytes=size,instrumentation=mode,count=1,warmup=0,
                                        build_config_sha256=mode,rows=[row],methodology=METHOD,wire_available=True,paper_inputs=True))
        validate_matched(cohorts)
        bad=copy.deepcopy(cohorts);bad[0]['rows'][0]['components_ns']=dict(dict.fromkeys(COMPONENTS,10),cpu_wire=20)
        with self.assertRaises(ValueError): validate_matched(bad)
        bad=copy.deepcopy(cohorts);bad[1]['rows'][0]['wire_ns']=200
        with self.assertRaises(ValueError): validate_matched(bad)
        bad=copy.deepcopy(cohorts);bad[2]['build_config_sha256']='different'
        with self.assertRaises(ValueError): validate_matched(bad)

    def test_full_grid_extensions_and_sustained_fallback(self):
        class Fixture(controller.Campaign):
            def unloaded(self, phase):
                assert phase=='full'
                controller.atomic(self.root/'full-unloaded.json',[])
            def stage(self, name, request):
                assert name!='rehearsal-validation'
                if name=='progress-plots' and 'unloaded' in request: self.unloaded_plotted=True
                if name=='progress-plots' and 'loaded' in request: self.loaded_updates=getattr(self,'loaded_updates',0)+1
                if name=='finalize':
                    assert self.loaded_updates>1
                    return dict({k:True for k in ('complete','hashes','boundaries','failure_accounting','worker_activity','pdf_dimensions','embedded_fonts')},previews=list('abcde'),files={p:'fixture' for p in 'abcde'})
                return {}
            def loaded(self, system, size, workers, rate, phase, trial=0, sustained=False):
                assert getattr(self,'unloaded_plotted',False), 'Loaded started before unloaded plots'
                assert phase in ('full','sustained')
                seconds=60 if sustained else 10
                # Full acquisition exceeds the rehearsal's estimated boundary.
                limit=100 if phase=='rehearsal' else 210
                if sustained: limit=150
                count=rate*seconds
                t=globals()['trial'](count if rate<limit else count-count//10,count)
                t['duration_ns']=seconds*1_000_000_000
                self.completed+=1
                return t
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            config=dict(initial_rate=50,max_extensions=10,scope='full',grid=[dict(system='LH',size=8,workers=1,rates=[50,100,150])])
            with patch.object(controller,'SYSTEMS',('LH',)), patch.object(controller,'SIZES',(8,)), patch.object(controller,'WORKERS',(1,)):
                Fixture(root,config).run()
            result=controller.read(root/'loaded.json')[0]
            self.assertGreater(max(p['rate'] for p in result['points']),max(result['rates']))
            self.assertGreater(len(result['confirmations']),1)
            self.assertLess(result['confirmed']['rate'],150)
            self.assertEqual(controller.read(root/'status.json')['stage'],'complete')

    def test_rehearsal_stops_before_full_collection(self):
        class Fixture(controller.Campaign):
            def unloaded(self, phase):
                self.assert_phase(phase)
            def assert_phase(self, phase):
                if phase != 'rehearsal': raise AssertionError('Full acquisition started')
            def stage(self, name, request):
                if name not in ('preflight','build','rehearsal-validation'):
                    raise AssertionError('Unexpected stage '+name)
                return dict(previews=list('abcd'),files={p:'fixture' for p in 'abcd'})
            def loaded(self, system, size, workers, rate, phase, **kwargs):
                self.assert_phase(phase)
                self.completed += 1
                return trial(100 if rate < 100 else 90)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            # Omitted scope is deliberately rehearsal-only, including legacy manifests.
            Fixture(root,dict(initial_rate=50,max_extensions=10)).run()
            state=controller.read(root/'status.json')
            self.assertEqual(state['stage'],'rehearsal-complete')
            self.assertFalse(state['full_collection_started'])
            self.assertFalse((root/'grid.json').exists())
            self.assertFalse((root/'loaded.json').exists())

    def test_notification_subprocess_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); sink=root/'queue.py'; log=root/'queue.jsonl'
            sink.write_text("import json,sys\nfrom pathlib import Path\np=Path(sys.argv[1])\nwith p.open('a') as f: f.write(json.dumps(sys.argv[2:])+'\\n')\nraise SystemExit(1 if p.with_suffix('.fail').exists() else 0)\n")
            config=dict(thread='fixture',notify=[controller.sys.executable,str(sink),str(log)])
            controller.atomic(root/'status.json',dict(stage='failed',error='fixture failure'))
            log.with_suffix('.fail').touch()
            self.assertFalse(controller.notify(root,config,retry=False))
            log.with_suffix('.fail').unlink()
            self.assertTrue(controller.notify(root,config,retry=False))
            controller.atomic(root/'status.json',dict(stage='complete',previews=['fixture.png']))
            self.assertTrue(controller.notify(root,config,retry=False))
            messages=[json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(len(messages),3)
            self.assertEqual(messages[0][:2],['--thread','fixture'])
            self.assertIn('complete',messages[-1][-1])

    def test_notification_retry_no_acquisition(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); controller.atomic(root/'status.json',dict(stage='failed',error='fixture'))
            config=dict(thread='fixture',notify=['queue-fixture'])
            with patch.object(controller.subprocess,'run') as run:
                run.return_value.returncode=1; run.return_value.stderr='temporary failure'
                self.assertFalse(controller.notify(root,config,retry=False))
                run.return_value.returncode=0; run.return_value.stderr=''
                self.assertTrue(controller.notify(root,config,retry=False))
                controller.notify(root,config,retry=False)
                self.assertEqual(run.call_count,2)
                self.assertEqual(run.call_args.args[0][1:3],['--thread','fixture'])
                self.assertEqual(controller.read(root/'notification.json')['attempts'],2)
            controller.atomic(root/'status.json',dict(stage='complete',previews=['fixture.pdf']))
            with patch.object(controller.subprocess,'run') as run:
                run.return_value.returncode=0; run.return_value.stderr=''
                self.assertTrue(controller.notify(root,config,retry=False))


if __name__=='__main__': unittest.main()
