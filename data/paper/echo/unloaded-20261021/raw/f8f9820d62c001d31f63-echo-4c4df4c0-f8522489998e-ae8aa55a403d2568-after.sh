python3 - <<'ECHOPROBE'
import hashlib,urllib.request
def module(url,expected):
 data=urllib.request.urlopen(url,timeout=30).read()
 if hashlib.sha256(data).hexdigest()!=expected: raise ValueError('Helper hash mismatch')
 namespace={'__name__':'campaign_helper'};exec(compile(data,url,'exec'),namespace);return namespace
dut=module('http://192.168.255.254:18859/dut-fa0be265b623.py','fa0be265b623b1dfbb8262688dddf29fcf60069e86cdc19d1f5a9c97ff87155d')
peer=module('http://192.168.255.254:18859/peer-1f7bb0c977c5.py','1f7bb0c977c5f5dedbbd787bad852d81b8f5230365745422c913b56daf9d9f34')
dut['snapshot']({'image': '/dev/shm/echo-shared-54b71b54c959.img', 'image_sha256': '54b71b54c959851db39a3210f2384d4eba01833f1564a910c2d11b9ede30561c', 'service_root': '/dev/shm/echo-4c4df4c0-f8522489998e-ae8aa55a403d2568-service', 'binary_path': '/nix/store/910q9xjj63q0p5dkrbv40n7vrj5l577s-lauberhorn-app-microbenchmarks-aarch64-unknown-linux-gnu-0.0.1/bin/microbenchmarks', 'binary_sha256': '9634c65815ba2db27c6b87ee3422e75c7db8679d80b4489ea248564c1fcdd4dc', 'runtime': '/nix/store/y6asi84w6dzvfv3zy3d1i1q4rbjc0siy-lauberhorn-rt-aarch64-unknown-linux-gnu-0.0.1', 'tracing_compiled': False, 'payload_bytes': 1432, 'workers': 1, 'mode': 'unloaded', 'instrumentation': 'off'},'/dev/shm/echo-4c4df4c0-f8522489998e-ae8aa55a403d2568-after',export=False)
peer['upload'](dut['Path']('/dev/shm/echo-4c4df4c0-f8522489998e-ae8aa55a403d2568-after'),'http://192.168.255.254:18859')

ECHOPROBE
