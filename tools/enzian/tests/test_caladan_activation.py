import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('caladan_activation',Path(__file__).resolve().parents[1]/'caladan_activation.py')
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)


def event(name,ns,**kw):
    return dict(event=name,ns=ns,pid=20,tid=21,cpu=4,subject=21,core=4,generation=7,peer=0,**{}) | kw


def fixture(preempt=False):
    events=[event('park_enter',10,peer=1),event('demand',30,subject=20,pid=99),
            event('allocate',40,peer=20,pid=99),event('run_request',50,generation=12,pid=99),
            event('park_resume',90),event('rx_observed',100)]
    kernel=[dict(ns=20,phase=2,tid=21,core=4,generation=0,target=0),
            dict(ns=80,phase=3,tid=21,core=4,generation=12,target=21)]
    if preempt:
        events += [event('cede_request',45,subject=31,peer=21,generation=9,pid=99),
                   event('preempt_received',55,tid=31,pid=30),
                   event('cede_enter',60,tid=31,pid=30,generation=9)]
        kernel += [dict(ns=65,phase=1,tid=31,core=4,generation=11,target=0),
                   dict(ns=70,phase=2,tid=31,core=4,generation=0,target=0)]
    return events,kernel,[dict(request_id=1,server_rx_observed_ns=100)]


class ActivationTests(unittest.TestCase):
    def test_both_paths_conserve_without_double_counting(self):
        for mode in ('parked','preempt'):
            events,kernel,rows=fixture(mode=='preempt')
            out=a.join(events,kernel,rows,mode)[0]
            self.assertEqual(out['activation_ns'],70)
            self.assertEqual(sum(x['ns'] for x in out['intervals']),70)
            self.assertEqual(out['grant_generation'],7)
            self.assertEqual(out['ksched_generation'],12)

    def test_reuse_wrong_generation_missing_signal_fail(self):
        e,k,r=fixture()
        with self.assertRaisesRegex(ValueError,'reused'): a.join(e,k,r+r,'parked')
        k[-1]['generation']=7
        with self.assertRaises(ValueError): a.join(e,k,r,'parked')
        e,k,r=fixture(True);e=[x for x in e if x['event']!='preempt_received']
        with self.assertRaisesRegex(ValueError,'delivery'): a.join(e,k,r,'preempt')

    def test_v5_observation_joins_same_activation(self):
        e,k,r=fixture()
        expected=a.join(e,k,r,'parked')
        r=[dict(request_id=1,accounting_version=5,server_rx_unblock_ns=100)]
        self.assertEqual(a.join(e,k,r,'parked'),expected)

    def test_decoder_rejects_loss_and_inflight_records(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'capture'
            header=[0x43414c4143543031,1,1,1,0,0,0,0]
            row=[100,1,20,21,4,20,4,1,0,1]
            p.write_bytes(struct.pack('<8Q10Q',*header,*row))
            self.assertEqual(a.decode(p)[0]['event'],'demand')
            row[-1]=0;p.write_bytes(struct.pack('<8Q10Q',*header,*row))
            with self.assertRaisesRegex(ValueError,'uncommitted'): a.decode(p)
            row[-1]=1;header[4]=1;p.write_bytes(struct.pack('<8Q10Q',*header,*row))
            with self.assertRaisesRegex(ValueError,'overflow'): a.decode(p)

    def test_kernel_embedded_clock(self):
        rows=a.kernel_records('x caladan:activation: ns=42 phase=3 core=4 generation=12 tid=21 target=21')
        self.assertEqual(rows[0]['ns'],42)
        with self.assertRaises(ValueError): a.kernel_records('CPU:0 LOST 2 EVENTS')
