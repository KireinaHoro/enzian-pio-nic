"""Synthetic traces validate joins and rejection, never constitute board evidence."""
import copy
import csv
import json
from pathlib import Path
import tempfile
import unittest
from breakdown import hardware_paths, join_clients, join_requests, software_rows


def fixture(count=70, segments=2):
    events=[];sw=[]
    def e(name,t,**d):events.append(dict(event=name,time_ns=t,data=d))
    for i in range(count):
        t=i*10000;rid=i%64;pid=7+i%2;core=1+i%2
        e('RxCmacEntry',t)
        e('EthernetDecoder',t+100,PacketID=rid)
        e('NativeUdpDecoder',t+200,PacketID=rid)
        e('RxRpcEnqueueToHost',t+300,HostMsgID=rid,PacketID=rid,PacketDropped=0)
        e('SchedulerRequestQueued',t+400,HostMsgID=rid,ProcessID=pid)
        e('SchedulerRequestDispatched',t+500,HostMsgID=rid,ProcessID=pid,CoreID=core)
        e('EciRxDescSent',t+600,HostMsgID=rid,CoreID=core)
        tx=[]
        for j in range(segments):
            h=(i*segments+j)%64;p=h;s=t+1000+j*1000
            e('EciTxCommitRead',s,CoreID=core,HostMsgID=h,CacheLineIndex=h%2)
            e('EciTxSubmit',s+100,CoreID=core,HostMsgID=h,RelatedRxTrace=0x8000|rid)
            e('UdpHostPacket',s+200,CoreID=core,HostMsgID=h,PacketID=p)
            e('EthernetEncoder',s+300,PacketID=p)
            e('TxCmacExit',s+400)
            tx.append(dict(prepare=1000000+s-200,begin=1000000+s-100,
                           doorbell=1000000+s-50,end=1000000+s+150))
        sw.append(dict(key=str(i),source=str(pid),core=core,rx_id=rid,
            software=dict(server_rx_enter_ns=1000000+t+550,server_rx_unblock_ns=1000000+t+700),
            wire_key=(11211+pid,0x7f000001,32123,i%64),tx=tx))
    return sorted(events,key=lambda e:e['time_ns']),sw


class BreakdownTests(unittest.TestCase):
    def test_wrap_segments_and_interleaved_instances(self):
        hw,sw=fixture();r=join_requests(sw,hardware_paths(hw))
        self.assertEqual(len(r),70)
        self.assertTrue(all(len(x['tx'])==2 and x['hardware']['mac_to_mac_ns']==2400 for x in r))

    def test_dcs_clock_and_additive_conservation(self):
        from clock_alignment import align
        hw,sw=fixture(count=8);rs=join_requests(sw,hardware_paths(hw));controls=[]
        for r in rs:
            core=r['core'];d=r['hardware']['rx_descriptor']
            controls.append(dict(core=core,time_ns=d+50,direction='rx',domain=core,
                address=20000+core*1000,parity=0,opcode='ECI_CMD_MRSP_PEMD'))
            for t in r['tx']:
                address=10000+core*1000+128*(1-t['parity'])
                for opcode,time in [('ECI_CMD_MREQ_RLDD',t['commit']-20),('ECI_CMD_MRSP_PEMD',t['commit']+140)]:
                    controls.append(dict(core=core,time_ns=time,direction='tx',domain=core,
                        address=address,parity=1-t['parity'],opcode=opcode))
        controls.sort(key=lambda e:e['time_ns'])
        clock=align(rs,controls)
        self.assertLess(clock['uncertainty_ns'],100)
        for r in rs:
            self.assertAlmostEqual(sum(r['additive_ns'].values()),r['aligned_mac_to_mac_ns'])
        from breakdown import plot
        with tempfile.TemporaryDirectory() as tmp:
            for r in rs:
                r.update(port=11211,client=dict(pattern='single',operation='GET'),
                         rtt_ns=500000,outside_mac_ns=497600)
            plot(rs,clock,Path(tmp))
            self.assertTrue((Path(tmp)/'case-00.svg').is_file())
            self.assertEqual(len((Path(tmp)/'requests.csv').read_text().splitlines()),9)
        with self.assertRaisesRegex(ValueError,'DCS'):
            align(rs,[e for e in controls if e['direction']!='rx'])

    def test_wrong_relation_and_missing_trace_rejected(self):
        hw,_=fixture()
        wrong=copy.deepcopy(hw)
        next(e for e in wrong if e['event']=='EciTxSubmit')['data']['RelatedRxTrace']=0x803f
        with self.assertRaisesRegex(ValueError,'active RX'):hardware_paths(wrong)
        with self.assertRaisesRegex(ValueError,'without queued'):
            hardware_paths([e for e in hw if e['event']!='SchedulerRequestQueued'])
        with self.assertRaisesRegex(ValueError,'MAC/Ethernet'):
            hardware_paths([e for e in hw if not(e['event']=='TxCmacExit' and e['time_ns']==1400)])

    def test_reordered_worker_dispatch_is_not_process_fifo(self):
        hw,_=fixture(count=2,segments=1)
        # Two workers consume one process queue; later request delivers first.
        for e in hw:
            if 'ProcessID' in e['data']:e['data']['ProcessID']=7
            if e['event'] in ('SchedulerRequestDispatched','EciRxDescSent','EciTxCommitRead','EciTxSubmit','UdpHostPacket','EthernetEncoder','TxCmacExit') and e['time_ns']<10000:
                e['time_ns']+=20000
        r=hardware_paths(hw)
        self.assertEqual([x['rx_id'] for x in r],[1,0])

    def test_software_segments_and_reused_wire_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);server=root/'server.csv';events=root/'events.csv';client=root/'client.csv'
            def write(p,data):
                with p.open('w') as f:
                    w=csv.DictWriter(f,fieldnames=list(data[0]));w.writeheader();w.writerows(data)
                Path(str(p)+'.status.json').write_text(json.dumps(dict(loss_free=True)))
            requests=[];ev=[];clients=[]
            wire=(0x7f000001<<32)|(32123<<16)|5
            for i in range(2):
                t=10000*i;ref=0x8000|i
                requests.append(dict(request_id=wire,request_id_valid=1,ok=1,request_context=ref,
                    server_tid=17,rx_cpu_id=44,server_rx_unblock_ns=t+100,server_handler_exit_ns=t+2000))
                def e(code,time,a,b):ev.append(dict(event=code,timestamp_ns=t+time,tid=17,cpu=44,
                    request_id=wire if code!=6 else 0,request_context=ref,arg0=a,arg1=b))
                e(6,120,(0x7f000001<<32)|(32123<<16)|11211,32)
                for j in range(2):
                    offset=j*500
                    e(203,200+offset,32,(5<<32)|(j<<16)|2)
                    e(200,300+offset,32,(ref<<16)|32123)
                    e(202,500+offset,t+400+offset,t+450+offset)
                    e(201,510+offset,32,1)
                clients.append(dict(port=11211,source_ip='127.0.0.1',source_port=32123,
                    request_id=5,sent_ns=t,received_ns=t+3000,status='ok'))
            write(server,requests);write(events,ev);write(client,clients)
            r=software_rows([server],[events],{44:1})
            self.assertEqual(len(r),2)
            for x in r:x['hardware']=dict(mac_to_mac_ns=2000)
            join_clients(r,[client]);self.assertTrue(all(x['outside_mac_ns']==1000 for x in r))
            bad=copy.deepcopy(ev);next(e for e in bad if e['event']==203)['arg1']|=1<<16
            write(events,bad)
            with self.assertRaisesRegex(ValueError,'segment'):software_rows([server],[events],{44:1})


if __name__=='__main__':unittest.main()
