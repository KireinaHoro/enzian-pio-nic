"""Manifest overrides control acquisition and reject invalid budgets."""
import tempfile
import unittest
from pathlib import Path
from .settings import measurement_settings, validate_unloaded_counts
from .test_campaign import controller

class Settings(unittest.TestCase):
    def test_steps_and_validation(self):
        s=measurement_settings({})['loaded']['step_rpc_s']
        self.assertEqual(s['LH'],{'8':20000,'128':20000,'1432':5000})
        self.assertEqual(s['DPDK'],s['CS-A64'])
        self.assertEqual(s['DPDK'],{'8':50000,'128':50000,'1432':20000})
        for value in ({'loaded':{'repetitions':0}}, {'unloaded':{'warmup_requests':1024}},
                      {'loaded':{'measurement_ms':True}}, {'loaded':{'typo':5}},
                      {'loaded':{'step_rpc_s':{'LH':{'8':0}}}}):
            with self.assertRaises(ValueError):measurement_settings({'measurement':value})

    def test_loaded_request_uses_manifest(self):
        class Fixture(controller.Campaign):
            def stage(self,name,request):
                self.request=request
                return {'trial':{}}
            def status(self,*args,**kwargs):pass
        manifest={'measurement':{'loaded':{'warmup_ms':123,'measurement_ms':456,
                    'confirmation_ms':789},'rehearsal':{'loaded_warmup_ms':12,'loaded_measurement_ms':34}},
                  'generator_cpus':[16], 'flows':64}
        with tempfile.TemporaryDirectory() as d:
            c=Fixture(Path(d),manifest)
            for phase,sustained,warmup,duration in [('full',False,123,456),('sustained',True,123,789),('rehearsal',False,12,34)]:
                c.loaded('LH',8,1,20000,phase,sustained=sustained)
                self.assertEqual((c.request['warmup_ms'],c.request['duration_ms']),(warmup,duration))

    def test_unloaded_final_counts_follow_manifest(self):
        s=measurement_settings({'measurement':{'unloaded':{'warmup_requests':32,'measurement_requests':1500}}})['unloaded']
        c={'count':1500,'warmup':64,'chunks':[{},{}]}
        validate_unloaded_counts([c],s)
        with self.assertRaises(ValueError):validate_unloaded_counts([dict(c,count=10000)],s)
