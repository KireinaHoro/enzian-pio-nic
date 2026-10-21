"""Hash-verified private Nix image execution shared by RPC and trace roles."""
import re

PYTHON = '/nix/store/lauberhorn-client/bin/python3'
NAMESPACE = '''set -eu
mount --make-rprivate /
mkdir -p /nix/store
if mountpoint -q /nix/store; then umount /nix/store; fi
mount -o loop,ro "$1" /nix/store
export PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin
unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME
exec /nix/store/lauberhorn-client/bin/python3 -c "$2"
'''


def python_script(image, sha256, payload):
    """Host Python only verifies the image and launches its pinned interpreter."""
    if not image.startswith('/') or not re.fullmatch('[0-9a-f]{64}',sha256):
        raise ValueError('Absolute image path and SHA256 required')
    code=f'''import hashlib,subprocess
from pathlib import Path
image=Path({image!r})
h=hashlib.sha256()
with image.open('rb') as stream:
 for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
if h.hexdigest()!={sha256!r}:raise ValueError('Staged image hash mismatch')
subprocess.run(['unshare','--mount','bash','-c',{NAMESPACE!r},'lauberhorn-image',str(image),{payload!r}],check=True)
'''
    return "python3 - <<'LHIMAGE'\n"+code+"\nLHIMAGE\n"


def downloaded_python(image, image_sha, url, script_sha, suffix=''):
    if not re.fullmatch('[0-9a-f]{64}',script_sha):raise ValueError('Script SHA256 required')
    payload=f'''import hashlib,urllib.request
script=urllib.request.urlopen({url!r},timeout=30).read()
if hashlib.sha256(script).hexdigest()!={script_sha!r}:raise ValueError('Remote script hash mismatch')
'''+suffix
    return python_script(image,image_sha,payload)
