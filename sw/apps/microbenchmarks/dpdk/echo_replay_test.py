#!/usr/bin/env python3
"""Validate schema echo and both responder tracing builds via pcap; no timing claims."""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import re
import sys
import time
sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
from echo_registry import generate


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
    registry=generate(Path(__file__).resolve().parent.parent.joinpath('bench.x').read_text())
    program=int(re.search(r'ECHO_PROGRAM (0x[0-9a-f]+)',registry)[1],16)
    version=int(re.search(r'ECHO_VERSION (\d+)',registry)[1])
    for name,size,procedure in re.findall(r'X\((echo\d+), (\d+), (\d+),',registry):
        size,procedure=int(size),int(procedure)
        body=bytes((1+i)%256 for i in range(size))
        call=struct.pack('!10I',1,0,2,program,version,procedure,0,0,0,0)+body
        reply=struct.pack('!6I',1,1,0,0,0,0)+body
        for server,controlled,compiled in ((True,False,True),(True,False,False),(False,False,False),(True,True,False)):
            label=f'{size}-{compiled}-'+('server-controlled' if controlled else 'server' if server else 'client')
            rx=a.output/(label+'.rx.pcap'); tx=a.output/(label+'.tx.pcap')
            pcap(rx,[frame(call) if server else frame(reply,True)])
            binary=a.bin/(('echo_dpdk' if compiled else 'echo_dpdk_uninstrumented') if server else 'bench_dpdk')
            ips=['192.0.2.2','192.0.2.1'] if server else ['192.0.2.1','192.0.2.2']
            cmd=[str(binary.resolve()),'-l',str(core),'--no-huge','--no-pci','--no-shconf','--no-telemetry',
                 '--vdev',f'net_pcap0,rx_pcap={rx.resolve()},tx_pcap={tx.resolve()}','--',*ips,
                 '02:00:00:00:00:01', '49152' if server else '12345','1','0','10000',name,str((a.output/(label+'.csv')).resolve())]
            with (a.output/(label+'.log')).open('w') as log:
                env={k:v for k,v in os.environ.items() if not k.startswith(('LH_ECHO_','LH_BENCH_'))}
                if controlled:
                    stop=a.output/(label+'.stop')
                    ready=a.output/(label+'.ready')
                    env.update(LH_ECHO_STOP_FILE=str(stop.resolve()),LH_ECHO_READY_FILE=str(ready.resolve()))
                    with subprocess.Popen(cmd,env=env,stdout=log,stderr=subprocess.STDOUT) as run:
                        deadline=time.monotonic()+20
                        while not ready.exists() and run.poll() is None and time.monotonic()<deadline:
                            time.sleep(.01)
                        # Pcap has one immediately available request; allow processing before stop.
                        time.sleep(.2)
                        stop.touch()
                        run.wait(timeout=10)
                else:
                    run=subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=30)
            sent=packets(tx) if tx.exists() else []
            expected=reply if server else call
            passed=run.returncode==0 and len(sent)==1 and sent[0][42:]==expected
            results.append(dict(case=label,passed=passed,returncode=run.returncode))
    (a.output/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(results)); return not all(r['passed'] for r in results)


if __name__=='__main__':
    raise SystemExit(main())
