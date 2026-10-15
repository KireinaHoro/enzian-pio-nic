#!/usr/bin/env python3
"""Serve a dedicated campaign directory and accept bounded, non-overwriting uploads.

Run on the gateway bound to its lab-management IP; never serve a home/repo root.
Stop this temporary service after acquisition. Full FPGA captures use the
separate F_MAC1 preservation workflow, not this small-result upload endpoint.
"""
import argparse
from functools import partial
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import re

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--root',type=Path,required=True)
p.add_argument('--bind',required=True)
p.add_argument('--port',type=int,default=18843)
p.add_argument('--max-upload-bytes',type=int,default=128*1024*1024,
               help='Per-file bound; reserve campaign storage before increasing it')
a=p.parse_args();root=a.root.resolve()
if not root.is_dir():p.error('Create a dedicated campaign directory first')
if a.max_upload_bytes<1:p.error('Upload bound must be positive')

class Handler(SimpleHTTPRequestHandler):
    def do_PUT(self):
        name=self.path.removeprefix('/')
        try: size=int(self.headers.get('Content-Length','0'))
        except ValueError: size=0
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*',name) or not 0<size<=a.max_upload_bytes:
            self.send_error(400);return
        try:
            with (root/name).open('xb') as out:
                left=size
                while left:
                    data=self.rfile.read(min(left,1024*1024))
                    if not data:raise OSError('Incomplete upload')
                    out.write(data);left-=len(data)
        except FileExistsError:
            self.send_error(409,'Evidence already exists');return
        except OSError:
            self.send_error(500,'Upload failed; partial evidence retained');return
        self.send_response(201);self.end_headers()

HTTPServer((a.bind,a.port),partial(Handler,directory=str(root))).serve_forever()
