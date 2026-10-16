#!/usr/bin/python3
"""Owner-invoked setup only; never installs packages or starts/enables services."""
import json
import os
from pathlib import Path

os.umask(0o077)
source = Path(__file__).resolve().parents[1]
home = Path.home()
config = home / '.config/myct-transmission'
# Refuse to overwrite settings, including settings written by a running daemon.
config.mkdir(parents=True, exist_ok=True)
settings = {
    'rpc-enabled': True, 'rpc-bind-address': '127.0.0.1', 'rpc-port': 19091,
    'rpc-whitelist-enabled': True, 'rpc-whitelist': '127.0.0.1',
    'rpc-host-whitelist-enabled': True, 'rpc-host-whitelist': 'localhost',
    'rpc-authentication-required': False,
    'download-dir': str(home / 'Downloads/My-CT/Torrents'),
    'incomplete-dir-enabled': False, 'port-forwarding-enabled': False,
    'script-torrent-done-enabled': True,
    'script-torrent-done-filename': str(home / '.local/bin/myct-torrent-done'),
}
targets = [(source / 'myct-torrent', home / '.local/bin/myct-torrent'),
           (source / 'torrent/myct-torrent-done', home / '.local/bin/myct-torrent-done'),
           (source / 'torrent/myct-transmission.service', home / '.config/systemd/user/myct-transmission.service')]
for target in [config / 'settings.json'] + [target for _, target in targets]:
    if target.exists() or target.is_symlink():
        raise SystemExit(f'Já existe {target}; nada será sobrescrito. Consulte a atualização manual no relatório.')
root = home / 'Downloads/My-CT/Torrents'
if root.resolve() != root:
    raise SystemExit('Destino redirecionado por symlink; nada será instalado.')
with (config / 'settings.json').open('x') as stream:
    json.dump(settings, stream, indent=2)
for origin, target in targets:
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('xb') as stream:
        stream.write(origin.read_bytes())
    target.chmod(0o700 if target.name in {'myct-torrent', 'myct-torrent-done'} else 0o600)
print('Preparado. Nada foi iniciado. Revise e execute systemctl --user daemon-reload e enable --now myct-transmission.service somente com autorização.')
