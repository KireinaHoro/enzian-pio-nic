#!/usr/bin/env python3
"""Test real adapter/parser with socket-injected transport; not ECI acceptance."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from compatibility import run
from protocol import Client


def port():
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
        s.bind(('127.0.0.1',0))
        return s.getsockname()[1]


def launch(binary, native, power=18):
    number=port()
    env=os.environ.copy()
    env['LAUBERHORN_UDP']='1' if native else '0'
    process=subprocess.Popen([binary,'-p','0','-U',str(number),'-l','127.0.0.1','-t','2','-o',f'hashpower={power}'],
                             env=env,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    for _ in range(100):
        if process.poll() is not None:
            raise RuntimeError(process.stderr.read().decode())
        try:
            c=Client('127.0.0.1',number,timeout=0.05); c.stats(); c.close()
            return process,number
        except (OSError,TimeoutError):
            time.sleep(0.01)
    process.kill();process.wait()
    raise TimeoutError('server did not become ready')


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--adapter',required=True)
    p.add_argument('--upstream',required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    results={}
    for name,binary,native in [('upstream',args.upstream,False),('adapter_socket_transport',args.adapter,True)]:
        process,number=launch(binary,native)
        try:
            results[name]=run('127.0.0.1',number)
        finally:
            process.terminate();process.wait(timeout=5)
            (args.output/f'{name}.log').write_bytes(process.stderr.read())
    assert results['upstream']['checks']==results['adapter_socket_transport']['checks']
    process,number=launch(args.adapter,True,power=12)
    try:
        c=Client('127.0.0.1',number,timeout=0.1)
        for index in range(7500):
            try:
                response=c.request(f'set expand{index} 0 0 1\r\nx\r\n'.encode())
                assert response==b'STORED\r\n'
            except (OSError,TimeoutError):
                break
        assert process.wait(timeout=5)==78, 'expansion must fail the maintenance scope check'
        log=process.stderr.read()
        assert b'LAUBERHORN_SCOPE_FAILURE' in log
        (args.output/'expansion.log').write_bytes(log)
        results['expansion_scope_failure']=True
        c.close()
    finally:
        if process.poll() is None: process.kill();process.wait()
    results['scope']='Application compatibility with injected socket transport; no ECI, scheduler or board claim.'
    (args.output/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
    print('PASS: pinned upstream, real adapter with injected transport, expansion scope rejection')
