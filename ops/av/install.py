#!/usr/bin/python3
"""Explicit user install; refuse all existing targets, never enable timer."""
import os
from pathlib import Path
import pwd
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
# Load the adapter's directory checks, without running its main.
import runpy
av = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'myct-av'))
home = Path(pwd.getpwuid(os.getuid()).pw_dir)
repo = Path(__file__).resolve().parents[2]
os.umask(0o077)
config = home / '.config/myct-av'
for p in (av['ROOT'], av['STATE'], av['DB'], config):
    av['private_dir'](p)
targets = {
    home / '.local/bin/myct-av': (repo / 'ops/myct-av', 0o700),
    home / '.local/bin/myct-av-scan': (repo / 'server/myct-av-scan', 0o700),
    home / '.config/systemd/user/myct-av-update.service': (repo / 'ops/av/myct-av-update.service', 0o600),
    home / '.config/systemd/user/myct-av-update.timer': (repo / 'ops/av/myct-av-update.timer', 0o600),
    config / 'freshclam.conf': (None, 0o600),
}
for target, (source, _) in targets.items():
    if target.exists() or target.is_symlink():
        sys.exit(f'Recusado: destino existente: {target}. Revise manualmente; nada sobrescrito.')
    if source and not source.is_file():
        sys.exit(f'Compile primeiro: {source}')
for target, (source, mode) in targets.items():
    target.parent.mkdir(parents=True, exist_ok=True)
    data = source.read_bytes() if source else (repo / 'ops/av/freshclam.conf.in').read_text().replace('@DATABASE@', str(av['DB'])).encode()
    with target.open('xb') as f:
        f.write(data)
    target.chmod(mode)
subprocess.run(['/usr/bin/systemctl', '--user', 'daemon-reload'], check=True)
print('Instalado sem sudo. Timer NÃO ativado. Execute myct-av update e consulte update-status.')
