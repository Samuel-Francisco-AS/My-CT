#!/usr/bin/env python3
"""Local OpenSSH SFTP subsystem proof, no sshd changes, credentials or TCP port.
Not an Android/JSch authenticated integration test. Only own temporary directory.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parents[2]
work = repo / 'android' / '.work'
work.mkdir(exist_ok=True)
server = Path('/usr/libexec/openssh/sftp-server')
if not server.is_file() or not shutil.which('sftp'):
    raise SystemExit('Existing OpenSSH client/subsystem required; nothing installed.')

def quoted(path):
    return '"' + str(path).replace('\\', '\\\\').replace('"', '\\"') + '"'

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(65536):
            h.update(block)
    return h.hexdigest()

start = time.monotonic()
with tempfile.TemporaryDirectory(prefix='myct-2-sftp-', dir=work) as tmp:
    root = Path(tmp)
    source = root / 'origem ação.bin'
    remote = root / 'servidor'
    remote.mkdir()
    uploaded = remote / 'temporário.bin'
    published = remote / 'arquivo final ação.bin'
    existing = remote / 'existente.bin'
    downloaded = root / 'recebido.bin'
    existing.write_bytes(b'existing sentinel')
    with source.open('wb') as stream:
        for _ in range(128):
            stream.write(bytes(range(256)) * 256)  # 8 MiB; bounded generation.
    commands = [
        f'put {quoted(source)} {quoted(uploaded)}',
        f'ln {quoted(uploaded)} {quoted(published)}',
        f'get {quoted(published)} {quoted(downloaded)}',
        f'-ln {quoted(uploaded)} {quoted(existing)}',  # Expected refusal, keep batch running.
        f'ls {quoted(remote)}',
    ]
    batch = root / 'batch.txt'
    batch.write_text('\n'.join(commands) + '\n')
    result = subprocess.run(['sftp', '-q', '-D', str(server), '-b', str(batch)],
                            capture_output=True, timeout=30)
    # Do not print provider output: it contains absolute paths.
    assert result.returncode == 0, 'SFTP batch failed (private output suppressed)'
    assert existing.read_bytes() == b'existing sentinel', 'Existing destination changed'
    assert source.stat().st_size == published.stat().st_size == downloaded.stat().st_size
    assert digest(source) == digest(published) == digest(downloaded), 'SHA-256 mismatch'
    assert uploaded.stat().st_ino == published.stat().st_ino, 'Not a hardlink publication'
    print(json.dumps({'transport': 'local pipes; no SSH authentication',
                      'bytes_each_direction': source.stat().st_size,
                      'sha256_equal_three_files': True, 'existing_target_preserved': True,
                      'hardlink_publish': True, 'unicode_and_spaces': True,
                      'elapsed_seconds': round(time.monotonic() - start, 3)}))
