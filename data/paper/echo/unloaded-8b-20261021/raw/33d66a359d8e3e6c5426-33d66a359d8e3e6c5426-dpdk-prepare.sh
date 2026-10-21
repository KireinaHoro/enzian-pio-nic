set -eu
test "$(basename "$(readlink -f /sys/class/net/enP5p144s0np0/device/driver)")" = mlx5_core
python3 - <<'ECHOIMAGE'
import hashlib,os,urllib.request
from pathlib import Path
def sha(path):
 value=hashlib.sha256()
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
 return value.hexdigest()
image=Path('/dev/shm/echo-shared-54b71b54c959.img')
if image.exists():
 if sha(image)!='54b71b54c959851db39a3210f2384d4eba01833f1564a910c2d11b9ede30561c':raise ValueError('Existing image differs; retain it for diagnosis')
else:
 temporary=image.with_name(image.name+'.partial-'+str(os.getpid()))
 with urllib.request.urlopen('http://192.168.255.254:18859/client.img',timeout=120) as source,temporary.open('xb') as target:
  while True:
   block=source.read(1024*1024)
   if not block:break
   target.write(block)
 if sha(temporary)!='54b71b54c959851db39a3210f2384d4eba01833f1564a910c2d11b9ede30561c':raise ValueError('Image transfer hash mismatch')
 temporary.rename(image)

ECHOIMAGE
