import importlib.util
from pathlib import Path
import unittest

def load(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).resolve().parents[1]/(name+'.py'))
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
b=load('ipg_breakdown'); c=load('activation_trace')
class IPGTests(unittest.TestCase):
    def test_nested_partition(self):
        spans=[(0,100,42,'syscall'),(20,40,42,'irq'),(30,60,42,'off_cpu'),(70,90,99,'irq')]
        r=b.partition(0,120,42,spans)
        self.assertEqual(r,dict(off_cpu=30,irq=10,ipi=0,softirq=0,syscall=60,unattributed_ipg=20))
        self.assertEqual(sum(b.partition(35,75,42,spans).values()),40)
    def test_pairs_and_incomplete(self):
        text='\n'.join(f'x-42 [044] .... 1.{n:06}: {e}: {v}' for n,e,v in [
            (1,'sys_enter','NR 1'),(2,'irq_handler_entry','irq=3'),
            (3,'irq_handler_exit','irq=3'),(4,'sys_exit','NR 1'),(5,'ipi_entry','reason=x')])
        spans,incomplete=b.intervals(text)
        self.assertEqual(incomplete,1)
        self.assertEqual(b.partition(1000001000,1000004000,42,spans)['irq'],1000)
        with self.assertRaises(ValueError):b.intervals(text+'\nLOST 3 EVENTS')
    def test_switch_migration_and_mask(self):
        spans,_=b.intervals('a-42 [044] .... 1.000001: sched_switch: prev_pid=42 next_pid=9\n'
                            'b-9 [045] .... 1.000004: sched_switch: prev_pid=9 next_pid=42')
        self.assertIn((1000001000,1000004000,42,'off_cpu'),spans)
        self.assertEqual(c.cpu_mask('44,45,46,47'),('0000f000,00000000',[44,45,46,47]))
    def test_gap_and_migration(self):
        rows=[dict(request_id=str(i),server_tid='42',server_rx_enter_ns=str(i*100),
                   server_tx_exit_ns=str(i*100+30),rx_cpu_id='44',tx_cpu_id='44') for i in (1,2)]
        self.assertEqual(b.breakdown(rows,[])[0]['components_ns']['unattributed_ipg'],70)
        rows[1]['rx_cpu_id']='45'
        with self.assertRaises(ValueError):b.breakdown(rows,[])
if __name__=='__main__': unittest.main()
