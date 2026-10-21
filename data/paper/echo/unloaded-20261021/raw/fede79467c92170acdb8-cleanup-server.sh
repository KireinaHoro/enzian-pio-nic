python3 - <<'ECHOPROBE'
from pathlib import Path
import shutil
root=Path('/dev/shm/echo-122293d0-45e5a92881b8-f4f737d18c42784c-server')
if root.parent!=Path('/dev/shm') or not root.name.startswith('echo-'):raise ValueError('Unexpected cleanup root')
shutil.rmtree(root)
root.with_suffix('.tar.gz').unlink()

ECHOPROBE
