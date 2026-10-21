python3 - <<'ECHOPROBE'
from pathlib import Path
import shutil
root=Path('/dev/shm/echo-122293d0-0371503d8218-2d43066a6ee97038-server')
if root.parent!=Path('/dev/shm') or not root.name.startswith('echo-'):raise ValueError('Unexpected cleanup root')
shutil.rmtree(root)
root.with_suffix('.tar.gz').unlink()

ECHOPROBE
