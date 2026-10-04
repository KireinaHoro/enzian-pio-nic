import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('latency', pathlib.Path(__file__).resolve().parents[1] / 'plot_latency.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class LatencyAccounting(unittest.TestCase):
    def row(self):
        return dict(request_id=1, e2e_ns=1000, client_xdr_ns=10,
                    server_unmarshal_ns=20, server_marshal_ns=20, handler_ns=10,
                    server_rx_ns=50000, server_tx_ns=40,
                    server_rx_exit_ns=100000, server_tx_exit_ns=100100)

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
        # A missing TX completion must not be borrowed from the next request.
        later = [dict(ev, time_ns=700) if ev['event']=='TxCmacExit' else ev for ev in events]
        clean = self.row()
        clean.update(e2e_raw_ns=1005, trace_core_delivery_ns=200,
                     server_rx_exit_ns=240, server_tx_enter_ns=310, server_tx_exit_ns=390)
        m.attach_trace_segments(clean,rec,m.build_event_index(later),1,0,None)
        self.assertNotIn('trace_lh_cmac_to_cmac_ns',clean)

if __name__ == '__main__':
    unittest.main()
