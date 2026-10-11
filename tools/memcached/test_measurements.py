"""Synthetic correlation checks; these fixtures are not measurement evidence."""
import csv
import json
import os
import subprocess
import sys
import time
from pathlib import Path
import tempfile
import unittest
from latency import analyze
from matrix import cases


class MeasurementTests(unittest.TestCase):
    def test_trace_dump_signal_is_blocked_before_application_threads(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);binary=root/'fake-memcached'
            binary.write_text('#!'+sys.executable+'\nimport signal,time\n'
                'print(int(signal.SIGUSR2 in signal.pthread_sigmask(signal.SIG_BLOCK, [])),flush=True)\n'
                'time.sleep(.3)\n')
            binary.chmod(0o755)
            (root/'manifest.json').write_text(json.dumps({'applications':{'memcached':{'entryPoint':str(binary)}}}))
            matrix=root/'matrix.json'
            matrix.write_text(json.dumps({'cases':[{'physical_workers':1,'workers':[1],'ports':[11211]}]}))
            ordinary=min(os.sched_getaffinity(0));worker=ordinary+1
            for traced in (False,True):
                output=root/str(traced)
                subprocess.run([sys.executable,str(Path(__file__).with_name('launch.py')),
                    '--deployment',str(root),'--manifest',str(matrix),'--case','0',
                    '--output',str(output),'--ordinary-cpus',str(ordinary),'--worker-cpus',str(worker),
                    *(['--trace'] if traced else [])],check=True,capture_output=True)
                self.assertEqual((output/'server-11211.log').read_text().strip(),str(int(traced)))
            time.sleep(.3) # allow both finite fake application processes to exit

    def test_matrix_contains_required_pools_and_repetitions(self):
        records=list(cases(4,[100,200]))
        self.assertEqual(len(records),9*2*2*2*3*2*3)
        self.assertEqual({tuple(c['workers']) for c in records if c['pool_case']=='mixed'}, {(1,2,3,4,1)})
        self.assertTrue(all(c['oversubscribed'] for c in records if c['pool_case'].startswith('homogeneous')))

    def test_causal_join_and_missing_packet_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);server=root/'server.csv';events=root/'events.csv'
            def write(path,records):
                with path.open('w') as f:
                    w=csv.DictWriter(f,fieldnames=list(records[0]));w.writeheader();w.writerows(records)
                Path(str(path)+'.status.json').write_text(json.dumps(dict(loss_free=True)))
            base=1_000_000_000
            write(server,[dict(request_id=10,request_id_valid=1,ok=1,rx_cpu_id=44,
                server_rx_enter_ns=base+3900,server_rx_unblock_ns=base+4100,
                server_handler_enter_ns=base+4200,server_handler_exit_ns=base+9000)])
            write(events,[dict(event=200,tid=17,request_id=10,cpu=44,arg1=11211,timestamp_ns=base+5900),
                          dict(event=201,tid=17,request_id=10,cpu=44,arg1=1,timestamp_ns=base+6100)])
            def e(name,time,**data):return dict(event=name,time_ns=time,data=data)
            hw=[e('RxCmacEntry',1000),e('EthernetDecoder',1100,PacketID=7),
                e('RxRpcEnqueueToHost',2000,PacketID=7,HostMsgID=1),
                e('SchedulerRequestDispatched',3000,CoreID=1,HostMsgID=1),
                e('EciRxDescSent',4000,CoreID=1),e('EciTxCommitRead',6000,CoreID=1,HostMsgID=2),
                e('EciTxSubmit',6200,CoreID=1,HostMsgID=2),
                e('UdpHostPacket',6300,CoreID=1,HostMsgID=2,PacketID=8),
                e('EthernetEncoder',6400,PacketID=8),e('TxCmacExit',6500)]
            result=analyze([server],[events],hw,{44:1})
            self.assertEqual(result['requests'][10]['linux_cpu'],44)
            self.assertAlmostEqual(result['stages_ns']['scheduler queue']['p50'],1000,delta=2)
            with self.assertRaisesRegex(ValueError,'packet count mismatch'):
                analyze([server],[events],[x for x in hw if x['event']!='EciTxCommitRead'],{44:1})
            Path(str(server)+'.status.json').write_text(json.dumps(dict(loss_free=False)))
            with self.assertRaisesRegex(ValueError,'incomplete software trace'):
                analyze([server],[events],hw,{44:1})


if __name__=='__main__':unittest.main()
