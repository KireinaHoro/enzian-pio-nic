import importlib.util
import pathlib
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('latency', pathlib.Path(__file__).resolve().parents[1] / 'plot_latency.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class LatencyAccounting(unittest.TestCase):
    def row(self):
        return dict(request_id=1, e2e_ns=1000, client_xdr_ns=10,
                    server_unmarshal_ns=20, server_marshal_ns=20, handler_ns=10,
                    server_rx_ns=50000, server_tx_ns=40,
                    server_rx_exit_ns=100000, server_tx_exit_ns=100100)

    def test_tx_probe_split(self):
        row = dict(server_tx_enter_ns=1000, server_tx_doorbell_ns=1200,
                   server_tx_exit_ns=2000, server_timestamp_overhead_ns=20,
                   server_tx_ns=960)
        self.assertEqual(m.tx_precommit_split(row, 430, 900), (180, 130))
        with self.assertRaises(ValueError):
            m.tx_precommit_split(row, 299, 900)
        row['server_tx_doorbell_ns'] = 999
        with self.assertRaises(ValueError):
            m.tx_precommit_split(row, 430, 900)
        row.pop('server_tx_doorbell_ns')
        self.assertEqual(m.tx_precommit_split(row, 430, 900), (310, 0))

    def test_blocking_rx_not_service(self):
        row = self.row()
        result = m.breakdown(row)
        self.assertEqual(result['network_or_residual'], 890)
        self.assertEqual(sum(result.values()), row['e2e_ns'])

    def test_overattribution_rejected(self):
        row = self.row()
        row['trace_segments'] = {'bad': 1100}
        with self.assertRaises(ValueError):
            m.breakdown(row)

    def test_missing_server_not_silently_dropped(self):
        with self.assertRaises(SystemExit):
            m.build_rows({1: {}}, {})

    def test_reversed_trace_interval_rejected(self):
        with self.assertRaises(ValueError):
            m.add_segment({}, 'reversed', 100, 90)

    def test_event_index_sorts_input(self):
        events = [dict(event='x', time_ns=t, data={}) for t in (20, 10)]
        self.assertEqual(m.first_event_after(m.build_event_index(events),'x',0)['time_ns'],10)

    def test_historical_completion_alias(self):
        schema = {'payload_formats': {'lauberhorn_event': {
            'fields': {'event_id': {'offset': 0, 'width': 8}},
            'events': {'3': 'EciTxCtrlUnlocked', '4': 'EciTxDataLciaUlDone'},
        }}}
        self.assertEqual(m.event_decoder(schema)(3)[0], 'EciTxCtrlInvalidated')
        self.assertEqual(m.event_decoder(schema)(4)[0], 'EciTxDataLciaDone')

    def test_scheduler_ignores_later_preemption_and_earlier_activation(self):
        events = [dict(event=name,time_ns=t,data={'CoreID':1,'HostMsgID':1})
                  for name,t in [('SchedulerRequestQueued',100),
                                 ('SchedulerProcessRun',50),
                                 ('SchedulerRequestDispatched',150),
                                 ('PreemptCtrlIpiIssued',10000)]]
        rec = {'events': events[:3], 'SchedulerRequestDispatched':150}
        row = self.row()
        row.update(server_tx_enter_ns=200000, trace_core_delivery_ns=200)
        m.attach_trace_segments(row,rec,m.build_event_index(events),1,0,None)
        self.assertEqual(row['trace_segments']['lh_scheduler_queue'],50)
        self.assertNotIn('lh_scheduler_preempt',row['trace_segments'])
        self.assertNotIn('kernel_wakeup',row['trace_segments'])

    def test_interleaved_flush_domain_is_excluded_but_bounds_tx(self):
        events=[]
        for index, pid in enumerate([1894,1672,1894,1672]):
            for name,delta in [('SchedulerRequestQueued',0),('SchedulerRequestDispatched',10),('EciRxDescSent',20)]:
                events.append(dict(event=name,time_ns=100*index+delta,
                    data=dict(CoreID=1,HostMsgID=10+index,ProcessID=pid)))
        rows=[dict(self.row(),request_id=i+1,worker_id=0,server_rx_enter_ns=1000,
                   server_rx_exit_ns=2000+200*i) for i in range(2)]
        with patch.object(m,'attach_trace_segments') as attach:
            m.correlate_trace(rows,events,None,process_id=1672)
        self.assertEqual([r['trace_host_msg_id'] for r in rows],[11,13])
        self.assertEqual(attach.call_args_list[0].args[1]['next_dispatch_ns'],210)
        with self.assertRaisesRegex(ValueError, '1672, 1894.*TGID'):
            m.correlate_trace(rows,events,None,process_id=0)
        events[0]['data'].pop('ProcessID')
        with self.assertRaisesRegex(ValueError,'ProcessID'):
            m.correlate_trace(rows,events,None,process_id=1672)

    def test_sentinel_rx_id_cannot_borrow_later_reuse(self):
        events = []
        for index, host_id in enumerate((6, 6)):
            for name, delta in [('SchedulerRequestQueued', 0), ('SchedulerRequestDispatched', 10), ('EciRxDescSent', 20)]:
                observed = 63 if index == 0 and name == 'EciRxDescSent' else host_id
                events.append(dict(event=name, time_ns=100*index+delta,
                                   data=dict(CoreID=1, HostMsgID=observed, ProcessID=7)))
        rows = [dict(self.row(), request_id=i+1, worker_id=0,
                     server_rx_enter_ns=1000, server_rx_exit_ns=2000+100*i)
                for i in range(2)]
        with patch.object(m, 'attach_trace_segments'):
            with self.assertRaisesRegex(ValueError, 'ID mismatch'):
                m.correlate_trace(rows, events, None, process_id=7)
            m.correlate_trace(rows, events, None, process_id=7, rx_trace_invalid_host_id=63)
        self.assertEqual(rows[0]['trace_core_delivery_ns'], 20)
        self.assertEqual(rows[1]['trace_core_delivery_ns'], 120)
        self.assertTrue(rows[0]['trace_rx_id_recovered'])
        self.assertFalse(rows[1]['trace_rx_id_recovered'])
        duplicate = dict(events[2], time_ns=25)
        with self.assertRaisesRegex(ValueError, 'ambiguous RX'):
            m.correlate_trace(rows, events+[duplicate], None, process_id=7, rx_trace_invalid_host_id=63)
        with self.assertRaisesRegex(ValueError, 'missing RX'):
            m.correlate_trace(rows, [e for e in events if e is not events[2]], None,
                              process_id=7, rx_trace_invalid_host_id=63)

    def test_dpdk_components_are_outside_hardware_not_poll_wait(self):
        c=dict(transport='dpdk',client_call_enter_ns='100',client_tx_ready_ns='200',
               client_tx_submit_ns='250',client_rx_burst_ns='900',client_call_exit_ns='1000',
               timestamp_overhead_ns='10',ok='1')
        row=m.build_rows({1:c},{1:{}})[0]
        row['trace_segments']={'hardware':500}
        parts=m.breakdown(row)
        self.assertEqual(parts['client_packet_prepare'],90)
        self.assertEqual(parts['client_reply_validate'],90)
        self.assertEqual(parts['outside_lh_client_network'],210)
        self.assertEqual(sum(parts.values()),890)
        self.assertNotIn('client_poll_wait',parts)
        self.assertEqual(row['client_poll_wait_observed_ns'],650)
        self.assertEqual(row['client_timestamp_count'],5)
        c['client_tx_ready_ns']='300'
        with self.assertRaisesRegex(ValueError,'DPDK timestamps'):
            m.build_rows({1:c},{1:{}})

    def test_metadata_after_enqueue_does_not_select_prior_id_reuse(self):
        events=[dict(event='OncRpcCallDecoder',time_ns=t,data={'RpcID':3})
                for t in (100,100010)]
        index=m.build_event_index(events)
        self.assertEqual(m.unique_nearby_event(index,'OncRpcCallDecoder',100000,{'RpcID':3})['time_ns'],100010)
        events.append(dict(event='OncRpcCallDecoder',time_ns=100020,data={'RpcID':3}))
        with self.assertRaisesRegex(ValueError,'ambiguous'):
            m.unique_nearby_event(m.build_event_index(events),'OncRpcCallDecoder',100000,{'RpcID':3})

    def test_complete_trace_conserves_and_retains_dma_milestones(self):
        chain = [('RxCmacEntry',0), ('RxAfterCdcQueue',10),
                 ('EthernetDecoder',20), ('IpDecoder',30), ('UdpDecoder',40),
                 ('OncRpcCallDecoder',50), ('RxRpcEnqueueToHost',60),
                 ('SchedulerRequestQueued',100), ('SchedulerRequestDispatched',150),
                 ('EciTxCommitRead',300), ('EciTxCtrlInvalidate',310),
                 ('EciTxCtrlInvalidated',350), ('EciTxSubmit',400),
                 ('TxHostReqAccepted',410), ('TxAfterDmaRead',470),
                 ('OncRpcReplyEncode',460), ('UdpEncoder',480),
                 ('IpEncoder',490), ('EthernetEncoder',500),
                 ('TxBeforeCdcQueue',510), ('TxCmacExit',520)]
        events = [dict(event=name, time_ns=t,
                       data=dict(CoreID=1, HostMsgID=1, RpcID=1, PacketID=1))
                  for name,t in chain]
        rec = dict(events=events, SchedulerRequestDispatched=150, next_dispatch_ns=600)
        row = self.row()
        row.update(e2e_raw_ns=1005, trace_core_delivery_ns=200,
                   server_rx_exit_ns=240, server_tx_enter_ns=310, server_tx_exit_ns=390)
        m.attach_trace_segments(row,rec,m.build_event_index(events),1,0,None)
        self.assertEqual(sum(row['trace_segments'].values()),520)
        self.assertEqual(row['external_residual_ns'],480)
        self.assertEqual(row['external_residual_raw_ns'],485)
        self.assertEqual(row['trace_tx_commit_to_cmac_ns'],220)
        self.assertEqual(row['trace_tx_milestones_after_submit_ns']['TxAfterDmaRead'],70)
        self.assertEqual(sum(m.breakdown(row).values()),1000)
        delayed = [dict(ev,time_ns=15) if ev['event']=='RxCmacEntry' else ev for ev in events]
        with self.assertRaisesRegex(ValueError,'reversed trace interval'):
            m.attach_trace_segments(dict(row),rec,m.build_event_index(delayed),1,0,None,True)
        combined = dict(row)
        reordered = [dict(ev,time_ns=70) if ev['event']=='OncRpcCallDecoder' else ev for ev in events]
        m.attach_trace_segments(combined,rec,m.build_event_index(reordered),1,0,None,True)
        self.assertEqual(combined['trace_rpc_decode_to_host_observed_ns'],-10)
        self.assertEqual(combined['trace_segments']['lh_rx_rpc_decode_and_host_enqueue'],20)
        self.assertNotIn('lh_rx_rpc_decode',combined['trace_segments'])
        self.assertNotIn('lh_rx_host_enqueue',combined['trace_segments'])
        self.assertEqual(sum(combined['trace_segments'].values()),520)
        self.assertEqual(sum(m.breakdown(combined).values()),1000)
        # A missing TX completion must not be borrowed from the next request.
        later = [dict(ev, time_ns=700) if ev['event']=='TxCmacExit' else ev for ev in events]
        clean = self.row()
        clean.update(e2e_raw_ns=1005, trace_core_delivery_ns=200,
                     server_rx_exit_ns=240, server_tx_enter_ns=310, server_tx_exit_ns=390)
        m.attach_trace_segments(clean,rec,m.build_event_index(later),1,0,None)
        self.assertNotIn('trace_lh_cmac_to_cmac_ns',clean)

if __name__ == '__main__':
    unittest.main()
