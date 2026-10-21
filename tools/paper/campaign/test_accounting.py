"""Distinct boundary values catch allocation/reservation leaking into codecs."""
import csv
import json
from pathlib import Path
import tempfile
import unittest
from . import accounting
from .import_unloaded import dpdk, lauberhorn
from .import_caladan import caladan
from .wire import METHOD


def fixture(system):
    shared = dict(backend_enter=150, lookup_enter=155, lookup_exit=163,
                  rpc_enter=180, unmarshal_enter=185, alloc_enter=190,
                  alloc_exit=213, decode_enter=220, decode_exit=251,
                  unmarshal_exit=253, handler_enter=260, handler_exit=297,
                  outcome_enter=300, marshal_enter=305, reserve_enter=310,
                  reserve_exit=353, encode_enter=360, encode_exit=407,
                  marshal_exit=410, reply_enter=415, reply_ready=434)
    row = {'server_'+k+'_ns':v for k,v in shared.items()}
    if system=='LH':
        row.update({'server_'+k+'_ns':v for k,v in dict(rx_unblock=100,rx_exit=140,
                   tx_enter=440,tx_doorbell=450,tx_doorbell_return=499,tx_exit=510).items()})
    elif system=='DPDK':
        row.update({k+'_ns':v for k,v in dict(rx_poll_enter=90,rx_cqe=100,rx_return=110,
                   network_rx_begin=115,network_rx_end=130,rpc_rx_end=260,handler_end=297,
                   rpc_tx_end=410,network_tx_begin=440,network_tx_end=457,tx_submit_begin=460,
                   tx_pmd_enter=470,tx_doorbell=480,tx_return=510).items()})
    else:
        row.update({'server_'+k+'_ns':v for k,v in dict(rx_observed=100,rx_queue_enter=105,
                   rx_queue_exit=110,network_rx_enter=115,network_rx_exit=130,
                   dispatch_ready=135,dispatch_enter=150,network_tx_enter=440,
                   network_tx_exit=457,driver_enter=470,tx_doorbell=480,submit_return=510).items()})
    return row


class AccountingTests(unittest.TestCase):
    def test_exact_codec_and_runtime_components(self):
        for system in ('LH','DPDK','CS-A64'):
            with self.subTest(system=system):
                result=accounting.partition(fixture(system),system)
                parts=result['components_ns']; details=result['accounting']['runtime_details_ns']
                self.assertEqual(parts['rpc_receive'],31)
                self.assertEqual(parts['rpc_transmit'],47)
                self.assertEqual(parts['handler'],37)
                self.assertEqual(details['allocation'],23)
                self.assertEqual(parts['tx_submission'],43+{'LH':59,'DPDK':50,'CS-A64':53}[system])
                self.assertEqual(sum(parts.values()),410)
                self.assertEqual(sum(details.values()),parts['cpu_other'])
                self.assertEqual(details['other_runtime'],0)
                if system=='CS-A64': self.assertEqual(details['dispatch_wait'],15)
                if system!='LH': self.assertEqual(details['lookup'],8)

    def test_incomplete_and_overlapping_boundaries_fail(self):
        for system in ('LH','DPDK','CS-A64'):
            for key,value in (('decode_enter',0),('alloc_exit',252),('reply_ready',1000)):
                row=fixture(system);row['server_'+key+'_ns']=value
                with self.subTest(system=system,key=key), self.assertRaises(ValueError):
                    accounting.partition(row,system)
            row=fixture(system);del row['server_encode_exit_ns']
            with self.assertRaises(KeyError): accounting.partition(row,system)

    def test_importers_preserve_details_with_and_without_wire(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            def write(name,row):
                path=root/name
                with path.open('w') as f:
                    w=csv.DictWriter(f,fieldnames=row);w.writeheader();w.writerow(row)
                return path
            client=write('client.csv',dict(request_id=1,benchmark='echo8',ok=1,rpc_status=0,
                         warmup=0,client_call_enter_ns=1,client_rx_burst_ns=1000))
            wire=root/'wire.json'
            wire.write_text(json.dumps(dict(methodology=METHOD,fixture=False,
                                            rows=[dict(flow_id=0,request_id=1,wire_ns=700)])))
            status=root/'status.json';status.write_text(json.dumps(dict(rows=1,dropped=0,loss_free=True)))
            for system in ('LH','DPDK','CS-A64'):
                row=fixture(system)
                row.update(request_id=1,request_context=1,request_id_valid=1,ok=1,
                           reply_status=0,transport_status=0,request_bytes=8,response_bytes=8,
                           status=1,warmup=0,profile=1)
                server=write('server.csv',row)
                for wire_path in (None,wire):
                    options=dict(count=1,warmup=0,payload_bytes=8,instrumentation='breakdown',wire_path=wire_path)
                    if system=='LH': result=lauberhorn(client,server_path=server,setup_count=0,**options)
                    elif system=='DPDK': result=dpdk(client,server,**options)
                    else: result=caladan(client,server,status,**options)
                    self.assertEqual(result['cpu_partition_version'],3)
                    observation=result['rows'][0]
                    self.assertEqual(observation['accounting']['runtime_details_ns']['allocation'],23)
                    self.assertEqual(sum(observation['components_ns'].values()),700 if wire_path else 410)

    def test_deferred_execution_is_not_hot_inline_accounting(self):
        row=fixture('LH');row['execution_kind']=1
        with self.assertRaisesRegex(ValueError,'inline'): accounting.partition(row,'LH')

    def test_schema_detection(self):
        self.assertEqual(accounting.version([fixture('LH')]),3)
        self.assertEqual(accounting.version([{'server_unmarshal_enter_ns':1}]),2)
        with self.assertRaises(ValueError): accounting.version([fixture('LH'),{}])
