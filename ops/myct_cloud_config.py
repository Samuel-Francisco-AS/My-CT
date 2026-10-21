"""Non-secret cloud selection shared by setup and the existing archive engines."""
import json
import os
from pathlib import Path
import re
import stat

class CloudConfigError(ValueError):
    pass

def cloud_selection_file():
    return Path.home() / '.config/myct/cloud.json'

def cloud_validate_selection(remote, config):
    if not isinstance(remote, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]{0,63}:', remote):
        raise CloudConfigError('Remote inválido: informe somente NOME:, sem caminhos ou credenciais.')
    if not isinstance(config, str) or (config and (not config.startswith('/') or len(config) > 1024
            or any(ord(c) < 32 or ord(c) == 127 for c in config) or '..' in Path(config).parts)):
        raise CloudConfigError('Caminho de configuração deve ser absoluto, sem links ou traversal.')
    return {'remote': remote, 'config': config}

def cloud_selection():
    path = cloud_selection_file()
    data = {'remote': 'myct-gdrive:', 'config': ''}
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if (path.resolve() != path or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077 or info.st_nlink != 1 or info.st_size > 4096):
            raise CloudConfigError('Seleção de nuvem protegida/divergente; revisar manualmente.')
        try:
            data = json.loads(path.read_text())
            if not isinstance(data, dict) or set(data) != {'remote', 'config'}:
                raise ValueError()
        except (ValueError, OSError):
            raise CloudConfigError('Seleção de nuvem inválida; preservar e revisar manualmente.') from None
    selected = cloud_validate_selection(os.environ.get('MYCT_ARCHIVE_REMOTE', data['remote']),
                                        os.environ.get('RCLONE_CONFIG', data['config']))
    if selected['config'] and Path(selected['config']).resolve() != Path(selected['config']):
        raise CloudConfigError('Configuração redirecionada; preservar e revisar manualmente.')
    return selected
