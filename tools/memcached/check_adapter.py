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
from protocol import Client, HEADER


def port():
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
        s.bind(('127.0.0.1',0))
        return s.getsockname()[1]


def launch(binary, power=18):
    number=port()
    env=os.environ.copy()
    env.pop('LAUBERHORN_UDP', None)
    process=subprocess.Popen([binary,'-p','0','-U',str(number),'-l','127.0.0.1','-t','2','-R','1','-o',f'hashpower={power}'],
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
    for name,binary in [('upstream',args.upstream),('adapter_socket_transport',args.adapter)]:
        process,number=launch(binary)
        try:
            results[name]=run('127.0.0.1',number)
        finally:
            process.terminate();process.wait(timeout=5)
            (args.output/f'{name}.log').write_bytes(process.stderr.read())
    assert results['upstream']['checks']==results['adapter_socket_transport']['checks']
    process,number=launch(args.adapter)
    try:
        c=Client('127.0.0.1',number,timeout=1)
        # Force conn_new_cmd's fairness yield with -R 1 while the same borrow
        # still contains another command. Only the final command responds.
        command=(b'set first 0 0 1 noreply\r\na\r\n'
                 b'set second 0 0 1\r\nb\r\n')
        assert c.request(command)==b'STORED\r\n'
        assert c.request(b'get first\r\n')==b'VALUE first 0 1\r\na\r\nEND\r\n'
        assert c.request(b'get second\r\n')==b'VALUE second 0 1\r\nb\r\nEND\r\n'
        # Dropped input, including an incomplete item, must release and reset
        # without contaminating the next datagram or freeing transport memory.
        for raw in [b'', b'\x00'*7, HEADER.pack(60000,0,1,0),
                    HEADER.pack(60001,0,2,0)+b'get first\r\n',
                    HEADER.pack(60002,0,1,0)+b'get first',
                    HEADER.pack(60003,0,1,0)+b'set broken 0 0 10\r\nx']:
            c.socket.send(raw)
            time.sleep(0.01)
            assert c.request(b'get first\r\n')==b'VALUE first 0 1\r\na\r\nEND\r\n'
        c.close()
        results['borrow_lifetime_and_local_continuations']=True
    finally:
        process.terminate();process.wait(timeout=5)
        (args.output/'borrow-lifetime.log').write_bytes(process.stderr.read())
    process,number=launch(args.adapter,power=12)
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
