"""Software queue boundaries, identity and missing external-wire evidence."""
import csv
from pathlib import Path
import tempfile
import unittest
from .import_unloaded import client_rows, cpu_partition, dpdk, lauberhorn


class Unloaded(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.root=Path(self.directory.name)
        self.client=[dict(request_id=i,benchmark='echo128',ok=1,rpc_status=0,warmup=int(i==1),
                          client_call_enter_ns=i*10000,client_rx_burst_ns=i*10000+2000) for i in range(1,4)]
        names=('rx_poll_enter','rx_cqe','rx_return','network_rx_begin','network_rx_end','rpc_rx_end',
               'handler_end','rpc_tx_end','network_tx_begin','network_tx_end','tx_submit_begin',
               'tx_pmd_enter','tx_doorbell','tx_return')
        self.server=[dict(request_id=i,status=1,warmup=int(i==1),profile=1,
                          **{k+'_ns':i*10000+100+10*n for n,k in enumerate(names)}) for i in range(1,4)]

    def write(self,name,rows):
        path=self.root/name
        with path.open('w') as stream:
            writer=csv.DictWriter(stream,fieldnames=rows[0]);writer.writeheader();writer.writerows(rows)
        return path

    def test_queue_components_without_fabricated_wire(self):
        c=dpdk(self.write('client.csv',self.client),self.write('server.csv',self.server[::-1]),
               count=2,warmup=1,payload_bytes=128,instrumentation='breakdown')
        self.assertFalse(c['wire_available']);self.assertFalse(c['paper_inputs'])
        for row in c['rows']:
            self.assertIsNone(row['wire_ns']);self.assertNotIn('cpu_wire',row['components_ns'])
            self.assertEqual(row['components_ns']['rx_queue'],10)
            self.assertEqual(row['components_ns']['tx_submission'],30)
            self.assertEqual(sum(row['components_ns'].values()),row['cpu_duration_ns'])
        self.server[-1]['request_id']=4
        with self.assertRaisesRegex(ValueError,'XID sets'):
            dpdk(self.root/'client.csv',self.write('bad.csv',self.server),count=2,warmup=1,payload_bytes=128,instrumentation='breakdown')

    def test_reject_missing_or_reversed_queue_boundaries(self):
        self.server[0]['rx_cqe_ns']=0
        with self.assertRaisesRegex(ValueError,'queue/software'):
            dpdk(self.write('client.csv',self.client),self.write('server.csv',self.server),
                 count=2,warmup=1,payload_bytes=128,instrumentation='breakdown')
        with self.assertRaisesRegex(ValueError,'Overlapping'):
            cpu_partition(1,100,{'handler':(2,30),'rpc_receive':(20,40)})

    def test_schema_and_single_outstanding(self):
        for size in (0,8,128,1432):
            for row in self.client:row['benchmark']='echo'+str(size)
            self.assertEqual(len(client_rows(self.write('c.csv',self.client),2,1,size)),3)
        self.client[1]['client_call_enter_ns']=11000
        with self.assertRaisesRegex(ValueError,'single-outstanding'):
            client_rows(self.write('c.csv',self.client),2,1,1432)

    def test_lh_uninstrumented_needs_no_trace(self):
        c=lauberhorn(self.write('c.csv',self.client),count=2,warmup=1,setup_count=16,
                    instrumentation='off',payload_bytes=128)
        self.assertFalse(c['wire_available']);self.assertEqual(len(c['rows']),3)
