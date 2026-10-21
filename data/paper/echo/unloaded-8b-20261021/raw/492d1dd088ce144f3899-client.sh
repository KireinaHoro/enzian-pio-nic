python3 - <<'LHIMAGE'
import hashlib,subprocess
from pathlib import Path
image=Path('/dev/shm/caladan-campaign-d5fca85d7870.img')
h=hashlib.sha256()
with image.open('rb') as stream:
 for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
if h.hexdigest()!='d5fca85d78708544ad8e2bf178201ae722abe3f05476970323fa951177a71ccf':raise ValueError('Staged image hash mismatch')
subprocess.run(['unshare','--mount','bash','-c','set -eu\nmount --make-rprivate /\nif mountpoint -q /nix/store; then umount /nix/store; fi\nmount -o loop,ro "$1" /nix/store\nexport PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin\nunset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME\nexec /nix/store/lauberhorn-client/bin/python3 -c "$2"\n','lauberhorn-image',str(image),"import hashlib,urllib.request\nscript=urllib.request.urlopen('http://192.168.255.254:18862/client-roles-peer.py',timeout=30).read()\nif hashlib.sha256(script).hexdigest()!='04b7212b5697a58098a5532b3cb152276851250c59d38ac3e998935022ea8690':raise ValueError('Remote script hash mismatch')\nimport json\nconfig=json.loads(urllib.request.urlopen('http://192.168.255.254:18862/echo-cd703733-944c8c0f097c-fa7550d53c017631-client.json',timeout=30).read())\nnamespace={'__name__':'campaign_role'}\nexec(compile(script,'peer.py','exec'),namespace)\nnamespace['run'](config)\n"],check=True)

LHIMAGE
