#!/usr/bin/python3
"""Opt-in real ClamAV regression using checkout worker, no installation or updates."""
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

REPO = Path(__file__).resolve().parents[2]
loader = importlib.machinery.SourceFileLoader('av_large', str(REPO / 'ops/myct-av'))
spec = importlib.util.spec_from_loader(loader.name, loader)
av = importlib.util.module_from_spec(spec); loader.exec_module(av)
av.BINARY = REPO / 'server/myct-av-scan'


def snapshot(p):
    fd = os.open(p, os.O_RDONLY | os.O_NOATIME)
    try:
        st = os.fstat(fd); h = hashlib.sha256()
        while chunk := os.read(fd, 1024*1024):
            h.update(chunk)
        return (h.hexdigest(), st.st_ino, st.st_size, st.st_mode, st.st_uid, st.st_gid,
                st.st_atime_ns, st.st_mtime_ns, st.st_ctime_ns,
                {x: os.getxattr(p, x) for x in os.listxattr(p)})
    finally:
        os.close(fd)


results = {}
with tempfile.TemporaryDirectory(prefix='myct-av-large-', dir=av.HOME) as d:
    root = Path(d)
    large = root / '80MiB-sparse.bin'
    with large.open('wb') as f:
        f.truncate(80*1024**2)
    benign = root / 'benign.txt'; benign.write_bytes(b'My-CT benign fixture\n')
    eicar = root / 'eicar.txt'
    eicar.write_bytes(b'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*')
    archive = root / 'expansion.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        with z.open('129MiB.bin', 'w') as f:
            for _ in range(129):
                f.write(b'\0' * 1024**2)
    for label, path, expected in (('large', large, 'CLEAN'), ('benign', benign, 'CLEAN'),
                                  ('eicar', eicar, 'DETECTED'), ('expansion', archive, 'INCOMPLETE')):
        available = next(int(x.split()[1]) for x in Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:'))
        assert available > 2*1024**2, 'Insufficient memory headroom for real test'
        before = snapshot(path)
        result = av.scan([str(path).encode().hex()])
        assert before == snapshot(path), f'Original modified: {label}'
        assert result['state'] == expected, result
        results[label] = result
    before = snapshot(large)
    with tempfile.TemporaryDirectory(dir=av.STATE) as tmp:
        env = {**os.environ, 'MYCT_AV_TMP': tmp}
        p = subprocess.run([str(av.BINARY), str(large).encode().hex(), '--watch-stdin'],
                           input=b'', capture_output=True, env=env, timeout=5)
    raw = json.loads(p.stdout)
    assert av.classify(raw)[0] == 'INCOMPLETE', raw
    assert before == snapshot(large)
    results['cancel'] = raw
print(json.dumps(results, indent=2))
