#!/usr/bin/env python3
"""Bounded UDP request/reply probes with Linux hardware timestamps.
Server requires raw hardware RX/TX timestamps for every numbered exchange.
Client uses ordinary kernel UDP. This validates timestamping, not null-RPC
performance; Python and the kernel stack are inside the observed interval.
"""
import argparse
import ctypes
import json
from pathlib import Path
import socket
import struct
import time

MAGIC = b'LHPTP001'
STAMP = 37
MSG_ERRQUEUE = 0x2000


def configure(fd, interface, enabled, tx_enabled=True):
    class Config(ctypes.Structure):
        _fields_=[('flags',ctypes.c_int),('tx',ctypes.c_int),('rx',ctypes.c_int)]
    class Ifreq(ctypes.Structure):
        _fields_=[('name',ctypes.c_char*16),('data',ctypes.c_void_p),('pad',ctypes.c_byte*16)]
    config=Config(0,1 if enabled and tx_enabled else 0,1 if enabled else 0)
    request=Ifreq(interface.encode(),ctypes.addressof(config))
    lib=ctypes.CDLL(None,use_errno=True)
    if lib.ioctl(fd,0x89b0,ctypes.byref(request)):
        err=ctypes.get_errno();raise OSError(err,'SIOCSHWTSTAMP')
    return dict(tx=config.tx,rx=config.rx)


def timestamp(ancillary):
    found=[]
    for level,kind,data in ancillary:
        if level==socket.SOL_SOCKET and kind==STAMP:
            values=struct.unpack('=6q',data[:48])
            if values[4] or values[5]:found.append(values[4]*1000000000+values[5])
    if len(found)!=1:raise RuntimeError(f'Expected one raw hardware timestamp, got {found}')
    return found[0]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['server','client'])
    p.add_argument('--interface',required=True)
    p.add_argument('--bind',required=True)
    p.add_argument('--peer',required=True)
    p.add_argument('--count',type=int,default=100)
    p.add_argument('--software-control',action='store_true')
    p.add_argument('--rx-only',action='store_true')
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    args.output.mkdir(parents=True,exist_ok=False)
    server=args.mode=='server';rows=[];report={'mode':args.mode,'passed':False,'rows':rows}
    sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET,socket.SO_BINDTODEVICE,args.interface.encode()+b'\0')
    sock.bind((args.bind,32194 if server else 32195))
    sock.connect((args.peer,32195 if server else 32194));sock.settimeout(15)
    def frame(index,reply):return MAGIC+struct.pack('!IB',index,reply)+bytes(33)
    def parse(data):
        if len(data)!=46 or data[:8]!=MAGIC:return None
        return struct.unpack('!IB',data[8:13])
    configured=False
    try:
        if server:
            report['configuration']=configure(sock.fileno(),args.interface,not args.software_control,not args.rx_only);configured=True
            if not args.software_control:sock.setsockopt(socket.SOL_SOCKET,STAMP,(0 if args.rx_only else 1)|4|64)
        (args.output/'ready').touch(exist_ok=False)
        for i in range(args.count):
            sock.settimeout(120 if server and i == 0 else 15)
            if not server:sock.send(frame(i,0))
            deadline=time.monotonic()+15
            while True:
                data,anc,flags,address=sock.recvmsg(2048,512)
                item=parse(data)
                if item is not None:
                    if item!=(i,0 if server else 1):raise RuntimeError(f'Unexpected packet {item}, expected {i}')
                    break
                if time.monotonic()>deadline:raise TimeoutError('No matching probe')
            if server and args.software_control:
                sock.send(frame(i,1));rows.append(dict(id=i,software_control=True));continue
            if server:
                rx=timestamp(anc);rows.append(dict(id=i,rx_hw_ns=rx))
                sent=frame(i,1);sock.send(sent)
                if args.rx_only:continue
                sock.setblocking(False)
                deadline=time.monotonic()+2
                while True:
                    try:
                        txdata,txanc,txflags,_=sock.recvmsg(2048,512,MSG_ERRQUEUE|socket.MSG_DONTWAIT)
                        tx=timestamp(txanc)
                        if MAGIC+struct.pack('!IB',i,1) not in txdata:raise RuntimeError('TX timestamp packet mismatch')
                        break
                    except BlockingIOError:
                        if time.monotonic()>deadline:raise TimeoutError('Missing hardware TX timestamp')
                        time.sleep(.001)
                if tx<=rx:raise RuntimeError('Nonpositive hardware interval')
                rows[-1].update(tx_hw_ns=tx,interval_ns=tx-rx)
                (args.output/'progress.json').write_text(json.dumps(rows))
            else:
                rows.append(dict(id=i,reply_validated=True));time.sleep(.01)
        report['passed']=True
    except Exception as exc:
        report['error']=repr(exc)
    finally:
        if configured:
            try:report['restored_timestamp_config']=configure(sock.fileno(),args.interface,False)
            except Exception as exc:report['restore_error']=repr(exc);report['passed']=False
        sock.close()
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='rows'}))
    return 0 if report['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
