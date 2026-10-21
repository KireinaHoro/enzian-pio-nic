python3 - <<'LHIMAGE'
import hashlib,subprocess
from pathlib import Path
image=Path('/dev/shm/echo-shared-54b71b54c959.img')
h=hashlib.sha256()
with image.open('rb') as stream:
 for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
if h.hexdigest()!='54b71b54c959851db39a3210f2384d4eba01833f1564a910c2d11b9ede30561c':raise ValueError('Staged image hash mismatch')
subprocess.run(['unshare','--mount','bash','-c','set -eu\nmount --make-rprivate /\nif mountpoint -q /nix/store; then umount /nix/store; fi\nmount -o loop,ro "$1" /nix/store\nexport PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin\nunset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME\nexec /nix/store/lauberhorn-client/bin/python3 -c "$2"\n','lauberhorn-image',str(image),"import hashlib,urllib.request\nscript=urllib.request.urlopen('http://192.168.255.254:18859/client-roles-peer.py',timeout=30).read()\nif hashlib.sha256(script).hexdigest()!='1f7bb0c977c5f5dedbbd787bad852d81b8f5230365745422c913b56daf9d9f34':raise ValueError('Remote script hash mismatch')\nimport json\nconfig=json.loads(urllib.request.urlopen('http://192.168.255.254:18859/echo-4c4df4c0-4455b4415063-782e99305e1fc88d-client.json',timeout=30).read())\nnamespace={'__name__':'campaign_role'}\nexec(compile(script,'peer.py','exec'),namespace)\nnamespace['run'](config)\n"],check=True)

LHIMAGE
