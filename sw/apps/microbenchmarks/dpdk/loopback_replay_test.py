#!/usr/bin/env python3
"""Validate both loopback endpoints through the pcap PMD; no timing claims."""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess


def frame(payload, server=False):
    src, dst = (bytes([192,0,2,2]), bytes([192,0,2,1])) if server else (bytes([192,0,2,1]), bytes([192,0,2,2]))
    udp = struct.pack('!4H', *( (12345,49152) if server else (49152,12345)), 8+len(payload), 0) + payload
    ip = bytearray(struct.pack('!BBHHHBBH4s4s',0x45,0,20+len(udp),0,0x4000,64,17,0,src,dst))
    total = sum(struct.unpack('!10H',ip))
    while total >> 16: total = (total & 65535)+(total >> 16)
    struct.pack_into('!H',ip,10,(~total)&65535)
    return bytes.fromhex('0200000000020200000000010800')+ip+udp


def pcap(path, frames):
    with path.open('wb') as f:
        f.write(struct.pack('<IHHIIII',0xa1b2c3d4,2,4,0,0,65535,1))
        for packet in frames:
            f.write(struct.pack('<IIII',0,0,len(packet),len(packet))+packet)


def packets(path):
    data = path.read_bytes(); pos = 24; result = []
    while pos < len(data):
        size = struct.unpack_from('<IIII',data,pos)[2]; pos += 16
        result.append(data[pos:pos+size]); pos += size
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('bin',type=Path); p.add_argument('output',type=Path)
    a=p.parse_args(); a.output.mkdir(parents=True,exist_ok=False)
    core=min(os.sched_getaffinity(0)); results=[]
    for size in (8,64,128,512,1432):
        body=struct.pack('!I',1)+bytes((1+i)%256 for i in range(4,size))
        call=struct.pack('!10I',1,0,2,0x23451113,1,1,0,0,0,0)+body
        reply=struct.pack('!6I',1,1,0,0,0,0)+body
        for server,batched in ((True,False),(False,False),(True,True)):
            label=f'{size}-'+('server-batched' if batched else 'server' if server else 'client')
            rx=a.output/(label+'.rx.pcap'); tx=a.output/(label+'.tx.pcap')
            pcap(rx,[frame(call) if server else frame(reply,True)])
            binary=a.bin/('echo_dpdk' if server else 'bench_dpdk')
            ips=['192.0.2.2','192.0.2.1'] if server else ['192.0.2.1','192.0.2.2']
            cmd=[str(binary.resolve()),'-l',str(core),'--no-huge','--no-pci','--no-shconf','--no-telemetry',
                 '--vdev',f'net_pcap0,rx_pcap={rx.resolve()},tx_pcap={tx.resolve()}','--',*ips,
                 '02:00:00:00:00:01', '49152' if server else '12345','1','0','10000',f'loopback{size}',str((a.output/(label+'.csv')).resolve())]
            with (a.output/(label+'.log')).open('w') as log:
                env=dict(os.environ,LH_ECHO_SOFTWARE_CONTROL='1')
                env.pop('LH_ECHO_BATCHED',None)
                if batched:env['LH_ECHO_BATCHED']='1'
                run=subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=30)
            sent=packets(tx) if tx.exists() else []
            expected=reply if server else call
            passed=run.returncode==0 and len(sent)==1 and sent[0][42:]==expected
            results.append(dict(case=label,passed=passed,returncode=run.returncode))
    (a.output/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(results)); return not all(r['passed'] for r in results)


if __name__=='__main__':
    raise SystemExit(main())
