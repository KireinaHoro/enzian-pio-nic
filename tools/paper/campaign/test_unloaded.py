"""Raw acquisition fixtures for unloaded identity and causal-bound validation."""
import csv
from pathlib import Path
import tempfile
import unittest

from .import_unloaded import client_rows, clock_bounds, dpdk, lauberhorn, uninstrumented


class Unloaded(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root=Path(self.directory.name)
        self.client=[dict(request_id=i,benchmark='echo128',ok=1,rpc_status=0,
                          warmup=int(i==1),client_call_enter_ns=i*10000,
                          client_rx_burst_ns=i*10000+2000) for i in range(1,4)]
        self.server=[dict(request_id=i,status=1,warmup=int(i==1),profile=0,
                          injected_delay_ns=0,rx_hw_ns=i*10000+400,
                          tx_hw_ns=i*10000+1400) for i in range(1,4)]

    def write(self,name,rows):
        path=self.root/name
        with path.open('w') as stream:
            writer=csv.DictWriter(stream,fieldnames=rows[0])
            writer.writeheader();writer.writerows(rows)
        return path

    def test_dpdk_xid_reordering_and_corruption(self):
        client=self.write('client.csv',self.client)
        server=self.write('server.csv',self.server[::-1])
        result=dpdk(client,server,count=2,warmup=1,payload_bytes=128,instrumentation='off')
        self.assertEqual([r['request_id'] for r in result['rows']],[1,2,3])
        self.server[-1]['request_id']=4
        with self.assertRaisesRegex(ValueError,'XID sets'):
            dpdk(client,self.write('server.csv',self.server),count=2,warmup=1,payload_bytes=128,instrumentation='off')
        self.client[1]['client_call_enter_ns']=11000
        with self.assertRaisesRegex(ValueError,'single-outstanding'):
            client_rows(self.write('client.csv',self.client),2,1,128)

    def test_rtt_only_preserves_client_boundary(self):
        client=self.write('client.csv',self.client)
        result=uninstrumented(client,system='LH',count=2,warmup=1,payload_bytes=128,instrumentation='off')
        self.assertEqual([r['rtt_ns'] for r in result['rows']],[2000]*3)
        self.assertTrue(all('mac_ns' not in r for r in result['rows']))
        with self.assertRaisesRegex(ValueError,'RTT-only'):
            uninstrumented(client,system='LH',count=2,warmup=1,payload_bytes=128,instrumentation='breakdown')

    def test_schema_payload_sizes_and_wrong_workload(self):
        for size in (0,8,128,1432):
            for row in self.client: row['benchmark']='echo'+str(size)
            path=self.write('client.csv',self.client)
            self.assertEqual(len(client_rows(path,2,1,size)),3)
            with self.assertRaisesRegex(ValueError,'Wrong workload'):
                client_rows(path,2,1,8 if size!=8 else 128)
            self.client[1]['ok']=0
            with self.assertRaisesRegex(ValueError,'Wrong workload'):
                client_rows(self.write('bad.csv',self.client),2,1,size)
            self.client[1]['ok']=1

    def test_lh_complete_capture_and_setup_boundary(self):
        client=self.write('client.csv',self.client)
        pairs=[dict(rx_mac_ns=i*10000+400,tx_mac_ns=i*10000+1400) for i in range(4)]
        kwargs=dict(count=2,warmup=1,setup_count=1,payload_bytes=128,instrumentation='off')
        result=lauberhorn(client,pairs,**kwargs)
        self.assertEqual([r['mac_ns'] for r in result['rows']],[1000]*3)
        self.assertFalse(result['causal']['client_mac']['applied_to_measurements'])
        with self.assertRaisesRegex(ValueError,'whole-capture RPC accounting'):
            lauberhorn(client,pairs[:-1],**kwargs)
        pairs[0]['tx_mac_ns']=None
        primed=lauberhorn(client,pairs,priming_count=1,**kwargs)
        self.assertEqual(primed['preparation']['priming_count'],1)
        self.assertEqual(len(primed['rows']),3)
        with self.assertRaisesRegex(ValueError,'Unpaired'):
            lauberhorn(client,pairs,**kwargs)

    def test_clock_association_rejects_inconsistent_order(self):
        clock_bounds([(100,200,1000,1200),(10100,10200,11000,11200)])
        with self.assertRaisesRegex(ValueError,'causal clock bounds'):
            clock_bounds([(100,200,1000,1200),(10100,10200,100000,100200)])

    def test_breakdown_conserves_own_mac_total(self):
        for row in self.server:
            row['profile']=1
            row.update(zip(('cpu_rx_ns','network_rx_begin_ns','network_rx_end_ns',
                            'network_tx_begin_ns','network_tx_end_ns','cpu_tx_ns'),
                           (100,200,300,500,600,700)))
        client=self.write('client.csv',self.client)
        result=dpdk(client,self.write('server.csv',self.server),count=2,warmup=1,payload_bytes=128,instrumentation='breakdown')
        self.assertEqual(result['rows'][0]['components_ns'],
                         dict(handler=200,rpc=200,network=200,host_mac=400))
        self.server[0]['cpu_tx_ns']=2000
        with self.assertRaisesRegex(ValueError,'partition'):
            dpdk(client,self.write('server.csv',self.server),count=2,warmup=1,payload_bytes=128,instrumentation='breakdown')

    def test_lh_streaming_header_precedes_last_payload_beat(self):
        client=self.write('client.csv',self.client)
        pairs=[dict(rx_mac_ns=i*10000+400,tx_mac_ns=i*10000+1400) for i in range(1,4)]
        server=self.write('runtime.csv',[dict(ok=1,server_rx_exit_ns=i*10000+800,
            server_handler_enter_ns=i*10000+850,server_handler_exit_ns=i*10000+900,
            server_tx_enter_ns=i*10000+950) for i in range(1,4)])
        for last_beat,overlap in ((450,0),(650,150)):
            events={eid:[i*10000+offset for i in range(1,4)] for eid,offset in
                    zip((3,4,9,44,45,2,5,6),(400,last_beat,500,700,1000,1100,1200,1400))}
            kwargs=dict(count=2,warmup=1,setup_count=0,payload_bytes=128,
                        instrumentation='breakdown',server_path=server,app_events=events)
            result=lauberhorn(client,pairs,**kwargs)
            for row in result['rows']:
                self.assertEqual(row['rx_streaming_overlap_ns'],overlap)
                self.assertEqual(sum(row['components_ns'].values()),1000)
                self.assertTrue(all(v>=0 for v in row['components_ns'].values()))
            events[4][0]=10800  # Last beat cannot follow host enqueue.
            with self.assertRaisesRegex(ValueError,'stage ordering'):
                lauberhorn(client,pairs,**kwargs)


if __name__=='__main__': unittest.main()
