#!/usr/bin/env python3
"""One peer-side B1-B4 transport/API campaign; retain observed wire bytes as JSON.

Run on a reserved peer with its NIC-VLAN address. Responses use real UDP sockets.
Timeout/late-reply tests deliberately leave the DUT otherwise without traffic.
"""
import argparse
import heapq
import json
from pathlib import Path
import socket
import struct
import threading
import time

PROGRAM, VERSION, PROCEDURE = 0x10234567, 0x12345678, 0x23456789
PEER_PROGRAM, PEER_VERSION = 0x456789AB, 0x3456789A
PORT, PEER_PORT = 22345, 23456

def words(*values):
    return struct.pack('!'+'I'*len(values), *values)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--nic',default='192.168.129.200')
    p.add_argument('--bind',required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--cycles',type=int,default=20)
    p.add_argument('--capacity',type=int,default=16)
    args=p.parse_args()
    if args.output.exists(): p.error('output must be new')
    observations=[]; cases=[]; errors=[]; stopped=threading.Event()
    responder=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    responder.bind((args.bind,PEER_PORT)); responder.settimeout(.01)
    def serve():
        delayed=[]; seq=0
        while not stopped.is_set():
            while delayed and delayed[0][0]<=time.monotonic():
                _,_,reply,peer=heapq.heappop(delayed); responder.sendto(reply,peer)
            try: packet,peer=responder.recvfrom(65536)
            except socket.timeout: continue
            row={'peer':list(peer),'hex':packet.hex()}; observations.append(row)
            try:
                if len(packet)<56 or packet[4:40]!=words(0,2,PEER_PROGRAM,PEER_VERSION,PROCEDURE,0,0,0,0):
                    raise ValueError('nested RPC header/byte order mismatch')
                xid=struct.unpack('!I',packet[:4])[0]
                ident,op,count,value=struct.unpack('!4I',packet[40:56])
                row.update(xid=xid,request_id=ident,operation=op)
                if peer[0]!=args.nic or peer[1]==PORT or not xid:
                    raise ValueError('incorrect endpoint source or XID')
                if op==100: continue
                status=3 if op==102 else 0
                reply=words(xid,1,0,0,0,status)+(packet[40:] if not status else b'')
                # Vary reply order for parallel futures; late replies arrive after expiry.
                delay=3.5 if op==101 else .002*(3-seq%4)
                heapq.heappush(delayed,(time.monotonic()+delay,seq,reply,peer)); seq+=1
            except (ValueError,struct.error) as exc: errors.append(str(exc))
    thread=threading.Thread(target=serve,daemon=True);thread.start()
    counter=0
    client=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    client.bind((args.bind,0));client.settimeout(8)
    def call(name,op,size=16,count=1,status=0):
        nonlocal counter
        counter+=1;ident=0x1A2B0000+counter;xid=0xC3D40000+counter
        payload=words(ident,op,count,0x13579BDF)+bytes((i*37+19)&255 for i in range(size-16))
        expected=bytearray(payload)
        if op in (3,4): expected[4:8]=words(0)
        row={'name':name,'id':ident,'status':'running'};cases.append(row)
        before=len(observations);start=time.monotonic()
        client.sendto(words(xid,0,2,PROGRAM,VERSION,PROCEDURE,0,0,0,0)+payload,(args.nic,PORT))
        reply,peer=client.recvfrom(65536)
        if peer!=(args.nic,PORT) or reply[:24]!=words(xid,1,0,0,0,status):
            raise ValueError(f'{name}: wrong reply header/source: {peer} {reply.hex()}')
        if not status:
            if op==7:
                accepted,rejected=struct.unpack('!2I',reply[32:40])
                if not (accepted==args.capacity and accepted+rejected==count):
                    raise ValueError('capacity admission was not bounded')
                expected[8:16]=words(accepted,rejected);row.update(accepted=accepted,rejected=rejected)
            if reply[24:]!=expected: raise ValueError(f'{name}: response bytes mismatch')
        elif len(reply)!=24: raise ValueError('unexpected error body')
        if op in (2,4,8,11,12) and len(observations)!=before:
            raise ValueError(f'{name}: unexpected external nested traffic')
        row.update(status='pass',seconds=round(time.monotonic()-start,6))
        print(json.dumps(row),flush=True)
    def incoming_capacity():
        nonlocal counter
        pending={}; accepted=0; rejected=0
        for i in range(32):
            counter+=1; ident=0x1A2B0000+counter; xid=0xC3D40000+counter
            payload=words(ident,6,1,0x13579BDF)
            pending[xid]=payload
            client.sendto(words(xid,0,2,PROGRAM,VERSION,PROCEDURE,0,0,0,0)+payload,(args.nic,PORT))
        while pending:
            reply,peer=client.recvfrom(65536)
            if len(reply)<24 or peer!=(args.nic,PORT): raise ValueError('invalid capacity response')
            xid,status=struct.unpack('!I',reply[:4])[0],struct.unpack('!I',reply[20:24])[0]
            payload=pending.pop(xid)
            if reply[:20]!=words(xid,1,0,0,0): raise ValueError('capacity response header')
            if status==0 and reply[24:]==payload: accepted+=1
            elif status==5 and len(reply)==24: rejected+=1
            else: raise ValueError('capacity response status/body')
        if accepted!=args.capacity or accepted+rejected!=32:
            raise ValueError('incoming capacity did not match/restored configured limit')
        cases.append({'name':'incoming-capacity','status':'pass','accepted':accepted,'rejected':rejected})
        time.sleep(4)
        call('after-incoming-capacity',1)
    result={'status':'fail','cases':cases,'nested_packets':observations,'peer_errors':errors}
    try:
        for size in (16,512,1024):
            for name,op in [('inline',0),('remote',1),('local',2),('forward-remote',3),('forward-local',4)]:
                call(f'{name}-{size}',op,size)
        call('parallel-reordered-futures',14,512,count=8)
        call('default-stack-rejected',8)
        call('explicit-error',9,status=3)
        call('oversize-encoder',10,status=5)
        call('fiber-exhaustion',11,status=5)
        call('after-fiber-exhaustion',1)
        call('pending-exhaustion',12,status=5)
        call('after-pending-exhaustion',1)
        call('remote-error-completion',13)
        for cycle in range(args.cycles):
            call(f'cancel-{cycle}',5)
            call(f'capacity-retire-{cycle}',7,count=32)
            call(f'capacity-reuse-{cycle}',1)
        incoming_capacity()
        call('deadline-without-traffic',6)
        time.sleep(4) # include deliberately late completion with no new requests
        call('after-late-reply',1)
        if errors: raise ValueError(str(errors))
        result['status']='pass'
    except (OSError,ValueError,KeyError,struct.error) as exc: result['error']=str(exc)
    finally:
        stopped.set();thread.join();client.close();responder.close()
        args.output.write_text(json.dumps(result,indent=2)+'\n')
    return int(result['status']!='pass')

if __name__=='__main__': raise SystemExit(main())
