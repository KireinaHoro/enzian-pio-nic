import csv
import json
from pathlib import Path
import tempfile
import unittest
from .import_caladan import caladan


class CaladanImportTests(unittest.TestCase):
    def test_partition_and_rejections(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            client = root/'client.csv'; server = root/'server.csv'; status = root/'status.json'
            def write(path, records):
                with path.open('w') as f:
                    w = csv.DictWriter(f, fieldnames=records[0].keys()); w.writeheader(); w.writerows(records)
            write(client, [dict(request_id=1, benchmark='echo8', ok=1, rpc_status=0,
                                warmup=0, client_call_enter_ns=1, client_rx_burst_ns=1000)])
            stages = ('rx_observed', 'rx_queue_enter', 'rx_queue_exit', 'network_rx_enter',
                      'network_rx_exit', 'dispatch_ready', 'dispatch_enter', 'unmarshal_enter',
                      'unmarshal_exit', 'handler_enter', 'handler_exit', 'marshal_enter',
                      'marshal_exit', 'network_tx_enter', 'network_tx_exit', 'driver_enter',
                      'tx_doorbell', 'submit_return')
            row = dict(request_context=1, request_id=1, request_id_valid=1, ok=1,
                       reply_status=0, transport_status=0, request_bytes=8, response_bytes=8)
            row.update({'server_'+s+'_ns':100+i*10 for i,s in enumerate(stages)})
            status.write_text(json.dumps(dict(rows=1,dropped=0,loss_free=True)))
            def parse():
                write(server,[row]); return caladan(client,server,status,count=1,warmup=0,payload_bytes=8)
            result=parse(); parts=result['rows'][0]
            self.assertEqual(result['system'], 'CS-A64')
            self.assertFalse(result['paper_inputs'])
            self.assertEqual(sum(parts['components_ns'].values()),parts['cpu_duration_ns'])
            self.assertEqual(parts['components_ns']['cpu_other'],50)
            row['request_context']=2
            with self.assertRaisesRegex(ValueError,'XID'): parse()
            row['request_context']=1;row['server_dispatch_enter_ns']=1
            with self.assertRaisesRegex(ValueError,'order'): parse()
            row['server_dispatch_enter_ns']=160
            status.write_text(json.dumps(dict(rows=1,dropped=1,loss_free=False)))
            with self.assertRaisesRegex(ValueError,'export'): parse()


if __name__ == '__main__': unittest.main()
