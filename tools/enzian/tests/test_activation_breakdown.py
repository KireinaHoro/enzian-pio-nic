import importlib.util
from pathlib import Path
import unittest
import csv
import json
import hashlib
import tempfile
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
def load(name):
    spec = importlib.util.spec_from_file_location(name,ROOT/(name+'.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module
b = load('activation_breakdown'); capture = load('activation_trace')

class ActivationBreakdownTests(unittest.TestCase):
    def sample(self):
        r = dict(worker=0,sequence=7,domain=12,tid=900,cold=1)
        r.update({k:2000+i*100 for i,k in enumerate(b.STAGES[:9])})
        text = 'worker-900 [044] .... 1.234: activation: '+' '.join(f'{k}={v}' for k,v in r.items())
        s = dict(request_id='1',server_tid='900',activation_worker='0',
                 server_activation_ioctl_exit_ns='2900',server_activation_ready_ns='3000',
                 server_rx_enter_ns='3100',rx_cpu_id='44')
        row = dict(request_id=1,trace_rx_cmac_entry_ns=100,trace_cpu_offset_ns=1000,
                   rx_cpu_id=44,datapath_core=1,e2e_raw_ns=5000,breakdown={'queue':4000,'other':1000})
        return text,s,{'rows':[row]}

    def test_partition_and_percentiles(self):
        text,s,a=self.sample()
        rows=b.join([s],b.parse_trace(text),a,'cold',100)
        self.assertEqual(rows[0]['activation_total_ns'],1100)
        self.assertEqual(sum(rows[0]['activation_components_ns'].values()),1100)
        self.assertEqual(sum(rows[0]['e2e_breakdown_ns'].values()),5000)
        self.assertEqual(b.summarize(rows)['0']['roundtrip']['p100'],5000)
        self.assertEqual(set(b.distribution([1,2,3])),{'count','p50','p90','p95','p99','p100'})

    def test_hot_does_not_recharge_activation(self):
        text,s,a=self.sample(); a['rows'][0]['trace_rx_cmac_entry_ns']=5000
        rows=b.join([s],[],a,'hot',100)
        self.assertNotIn('activation_components_ns',rows[0])
        with self.assertRaisesRegex(ValueError,'condition'):
            b.join([s],b.parse_trace(text),a,'cold',100)

    def test_missing_duplicate_reversed_and_lost(self):
        text,s,a=self.sample()
        with self.assertRaisesRegex(ValueError,'missing or ambiguous'):
            b.join([s],[],a,'cold',100)
        for broken in [text+'\n'+text,text.replace('claim=2400','claim=1900'),text+'\nLOST 2 EVENTS']:
            with self.assertRaises(ValueError): b.parse_trace(broken)
        s['server_activation_ioctl_exit_ns']='2700'
        with self.assertRaises(ValueError): b.join([s],b.parse_trace(text),a,'cold',100)

    def test_identity_ambiguity_and_cold_reuse(self):
        text,s,a=self.sample(); records=b.parse_trace(text)
        a['rows'][0]['datapath_core']=2
        with self.assertRaisesRegex(ValueError,'identity'):b.join([s],records,a,'cold',100)
        a['rows'][0]['datapath_core']=1
        a['rows'].append(dict(a['rows'][0],request_id=2))
        with self.assertRaisesRegex(ValueError,'multiple cold'):
            b.join([s,dict(s,request_id='2')],records,a,'cold',100)
        a['rows']=a['rows'][:1];a['rows'][0]['trace_rx_cmac_entry_ns']=2000
        with self.assertRaisesRegex(ValueError,'ambiguous'):b.join([s],records,a,'cold',100)

    def test_cli_four_worker_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            (d/'capture.json').write_text(json.dumps(dict(loss_free=True,returncode=0)))
            server=[];audit=[];traces=[]
            for worker in range(4):
                text,s,a=self.sample()
                s.update(request_id=str(worker+1),activation_worker=str(worker),rx_cpu_id=str(44+worker))
                a['rows'][0].update(request_id=worker+1,datapath_core=worker+1,rx_cpu_id=44+worker)
                server.append(s);audit.extend(a['rows']);traces.append(text.replace('worker=0','worker='+str(worker)))
            (d/'trace.txt').write_text('\n'.join(traces))
            with (d/'server.csv').open('w') as f:
                writer=csv.DictWriter(f,fieldnames=server[0]);writer.writeheader();writer.writerows(server)
            provenance=dict(server_sha256=hashlib.sha256((d/'server.csv').read_bytes()).hexdigest())
            (d/'audit.json').write_text(json.dumps(dict(provenance=provenance,rows=audit)))
            command=[sys.executable,str(ROOT/'activation_breakdown.py'),'--capture',str(d),
                     '--server',str(d/'server.csv'),'--audit',str(d/'audit.json'),
                     '--condition','cold','--expected-per-worker','1','--margin-ns','100',
                     '--output',str(d/'result.json')]
            subprocess.run(command,check=True,capture_output=True)
            result=json.loads((d/'result.json').read_text())
            self.assertEqual(len(result['rows']),4)
            self.assertEqual(result['metrics_ns']['all']['roundtrip']['count'],4)
            subprocess.run(command+['--expected-total','4'],check=True,capture_output=True)
            self.assertNotEqual(subprocess.run(command+['--expected-total','5'],capture_output=True).returncode,0)
            (d/'capture.json').write_text(json.dumps(dict(loss_free=False,returncode=0)))
            self.assertNotEqual(subprocess.run(command,capture_output=True).returncode,0)

    def test_loss_stats(self):
        good='overrun: 0\ncommit overrun: 0\ndropped events: 0\n'
        self.assertTrue(capture.loss_free({'cpu44':good}))
        self.assertFalse(capture.loss_free({}))
        self.assertFalse(capture.loss_free({'cpu44':'entries: 1'}))
        self.assertFalse(capture.loss_free({'cpu44':good.replace('events: 0','events: 1')}))

if __name__ == '__main__': unittest.main()
