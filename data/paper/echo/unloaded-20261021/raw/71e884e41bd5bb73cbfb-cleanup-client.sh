python3 - <<'ECHOPROBE'
from pathlib import Path
import shutil
root=Path('/dev/shm/echo-122293d0-29148e088ae5-ebd18500c49423ba-client')
if root.parent!=Path('/dev/shm') or not root.name.startswith('echo-'):raise ValueError('Unexpected cleanup root')
shutil.rmtree(root)
root.with_suffix('.tar.gz').unlink()

ECHOPROBE
