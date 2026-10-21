python3 - <<'ECHOPROBE'
import pathlib,subprocess
assert subprocess.check_output(['uname','-r'],text=True).strip()=='6.8.0-146-generic'
assert not any(line.startswith(('lauberhorn ','ksched ','fake_idle ')) for line in pathlib.Path('/proc/modules').read_text().splitlines())
assert pathlib.Path('/sys/class/net/enP5p144s0np0/device/driver').resolve().name=='mlx5_core'

ECHOPROBE
