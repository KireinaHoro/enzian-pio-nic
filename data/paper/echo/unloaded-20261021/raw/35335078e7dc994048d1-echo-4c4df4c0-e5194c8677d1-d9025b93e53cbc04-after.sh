python3 - <<'ECHOPROBE'
import hashlib,urllib.request
def module(url,expected):
 data=urllib.request.urlopen(url,timeout=30).read()
 if hashlib.sha256(data).hexdigest()!=expected: raise ValueError('Helper hash mismatch')
 namespace={'__name__':'campaign_helper'};exec(compile(data,url,'exec'),namespace);return namespace
dut=module('http://192.168.255.254:18859/dut-fa0be265b623.py','fa0be265b623b1dfbb8262688dddf29fcf60069e86cdc19d1f5a9c97ff87155d')
peer=module('http://192.168.255.254:18859/peer-1f7bb0c977c5.py','1f7bb0c977c5f5dedbbd787bad852d81b8f5230365745422c913b56daf9d9f34')
dut['snapshot']({'image': '/dev/shm/echo-shared-54b71b54c959.img', 'image_sha256': '54b71b54c959851db39a3210f2384d4eba01833f1564a910c2d11b9ede30561c', 'service_root': '/dev/shm/echo-4c4df4c0-e5194c8677d1-d9025b93e53cbc04-service', 'binary_path': '/nix/store/7sa629l4s813gfw70dbnrr8c447jw4sq-lauberhorn-app-microbenchmarks-aarch64-unknown-linux-gnu-0.0.1/bin/microbenchmarks', 'binary_sha256': '9af5665a49f8e722a71f2c087bc1e743db5657e5c636794361d0bfca3c3a7517', 'runtime': '/nix/store/82lkh8xzdmx5p1v5442wfrhdxkvin40v-lauberhorn-rt-aarch64-unknown-linux-gnu-0.0.1', 'tracing_compiled': True, 'payload_bytes': 128, 'workers': 1, 'mode': 'unloaded', 'instrumentation': 'breakdown'},'/dev/shm/echo-4c4df4c0-e5194c8677d1-d9025b93e53cbc04-after',export=True)
peer['upload'](dut['Path']('/dev/shm/echo-4c4df4c0-e5194c8677d1-d9025b93e53cbc04-after'),'http://192.168.255.254:18859')

ECHOPROBE
