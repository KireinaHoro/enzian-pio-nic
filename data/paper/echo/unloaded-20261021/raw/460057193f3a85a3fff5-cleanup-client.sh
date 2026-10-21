python3 - <<'ECHOPROBE'
from pathlib import Path
import shutil
root=Path('/dev/shm/echo-4c4df4c0-4455b4415063-333cef7498b2b0ff-client')
if root.parent!=Path('/dev/shm') or not root.name.startswith('echo-'):raise ValueError('Unexpected cleanup root')
shutil.rmtree(root)
root.with_suffix('.tar.gz').unlink()

ECHOPROBE
