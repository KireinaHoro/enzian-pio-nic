import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from handoff_breakdown import join, yield_entries


class HandoffBreakdown(unittest.TestCase):
    def fixture(self):
        audit=[dict(request_id=1,datapath_core=1,trace_request_queued_ns=1000,trace_cpu_offset_ns=10000)]
        activation=[dict(request_id=1,worker=0,tid=20,activation_sequence=3,
                         activation_path='cooperative',activation_components_ns={'mmio_to_rx':500},activation_total_ns=500)]
        timings=[dict(worker=0,sequence=3,read_begin=14000)]
        entries=[dict(cpu=44,tid=10,enter_ns=12000,exit_ns=18000)]
        events=[dict(event='SchedulerPreemptCore',time_ns=1020,data=dict(CoreID=1,
                     PreemptOutOfIdle=0,PreviousProcessID=8,ProcessID=18))]
        return audit,activation,timings,entries,events

    def test_partition_includes_outgoing_entry_without_double_counting(self):
        result=join(*self.fixture())[0]
        self.assertEqual(result['components_ns'],dict(queued_to_preempt_request=20,
            preempt_request_to_outgoing_syscall_entry=980,outgoing_syscall_entry_to_mmio=2000,mmio_to_rx=500))
        self.assertEqual(result['queue_to_rx_entry_ns'],3500)
        self.assertEqual(result['outgoing_tid'],10)

    def test_wrong_cpu_completed_or_incoming_syscall_is_rejected(self):
        for change in [dict(cpu=45),dict(exit_ns=13999),dict(tid=20)]:
            args=self.fixture();args[3][0].update(change)
            with self.assertRaisesRegex(ValueError,'outgoing yield'):join(*args)

    def test_ambiguous_preempt_and_syscall_fail(self):
        args=self.fixture();args[4].append(dict(args[4][0]))
        with self.assertRaisesRegex(ValueError,'FPGA PREEMPT'):join(*args)
        args=self.fixture();args[3].append(dict(args[3][0],tid=11))
        with self.assertRaisesRegex(ValueError,'outgoing yield'):join(*args)

    def test_ioctl_filter_and_quantized_timestamp(self):
        text=''' worker-10 [044] ..... 0.000012: sys_enter: NR 29 (3, 800c4c0c, abcd, 0, 0, 0)
 worker-10 [045] ..... 0.000020: sys_exit: NR 29 = 0
 worker-11 [044] ..... 0.000021: sys_enter: NR 29 (3, 800c4c0b, abcd, 0, 0, 0)
'''
        self.assertEqual(yield_entries(text),[dict(tid=10,cpu=44,enter_ns=12000,exit_ns=20000)])

if __name__=='__main__':unittest.main()
