#!/usr/bin/python3
"""Opt-in real Fedora tests; only own temporary fixtures. No update/timer activation."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import signal
import subprocess
import tempfile
import time
import zipfile

HOME = Path(pwd.getpwuid(os.getuid()).pw_dir)
CLI = HOME / '.local/bin/myct-av'
WORKER = HOME / '.local/bin/myct-av-scan'


def snap(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOATIME)
    try:
        st = os.fstat(fd)
        h = hashlib.sha256()
        while chunk := os.read(fd, 65536):
            h.update(chunk)
        return (h.hexdigest(), st.st_ino, st.st_size, st.st_mode, st.st_uid, st.st_gid,
                st.st_atime_ns, st.st_mtime_ns, st.st_ctime_ns,
                {name: os.getxattr(path, name) for name in os.listxattr(path)})
    finally:
        os.close(fd)


def run(path, *extra):
    before = snap(path)
    started = time.monotonic()
    p = subprocess.Popen([str(CLI), 'scan', str(path).encode().hex(), *extra],
                         stdout=subprocess.PIPE, stdin=subprocess.PIPE)
    peak_pss = 0
    # Observational sampling of clamscan alone; ru_maxrss is exact high water RSS.
    while p.poll() is None:
        available = next(int(x.split()[1]) for x in Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:'))
        if available < 1024**2:
            p.terminate(); p.wait(timeout=5)
            raise RuntimeError('Validação interrompida: MemAvailable < 1 GiB.')
        for proc in Path('/proc').glob('[0-9]*'):
            try:
                if (proc / 'comm').read_text().strip() == 'clamscan' and proc.stat().st_uid == os.getuid():
                    for line in (proc / 'smaps_rollup').read_text().splitlines():
                        if line.startswith('Pss:'):
                            peak_pss = max(peak_pss, int(line.split()[1]))
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                pass
        time.sleep(.1)
    out, _ = p.communicate()
    result = json.loads(out)
    assert before == snap(path), ('Fixture changed', path)
    result['elapsed_s'] = round(time.monotonic() - started, 3)
    result['sampled_peak_pss_kib'] = peak_pss
    return result


with tempfile.TemporaryDirectory(prefix='myct-av-proof-', dir=HOME) as tmp:
    root = Path(tmp)
    benign = root / "ação ' $(touch NEVER); [*]?.txt"
    benign.write_bytes(b'My-CT benign fixture\n')
    # Standard inert EICAR test string, not executable malware.
    eicar = root / 'eicar.txt'
    eicar.write_bytes(b'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*')
    results = {}
    results['benign'] = run(benign)
    assert results['benign']['state'] == 'CLEAN', results['benign']
    empty = root / 'empty'; empty.write_bytes(b'')
    results['empty_load_baseline'] = run(empty)
    assert results['empty_load_baseline']['state'] in ('CLEAN', 'INCOMPLETE'), results['empty_load_baseline']
    results['eicar'] = run(eicar)
    assert results['eicar']['state'] == 'DETECTED', results['eicar']
    archive = root / 'too-many.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for i in range(1002):
            z.writestr(f'{i}.txt', f'Benign entry {i}')
    results['archive_limit'] = run(archive)
    assert results['archive_limit']['state'] == 'INCOMPLETE', results['archive_limit']
    huge = root / 'oversized'
    with huge.open('wb') as f:
        f.truncate(2000000001)
    results['size_limit'] = run(huge)
    assert results['size_limit']['state'] == 'INCOMPLETE'
    link = root / 'link'; link.symlink_to(benign)
    dl = root / 'directory-link'; dl.symlink_to(root, target_is_directory=True)
    fifo = root / 'fifo'; os.mkfifo(fifo)
    invalid = [link, dl / benign.name, root, fifo, root / 'missing', '/etc/passwd',
               f'{root}/../{root.name}/{benign.name}']
    for path in invalid:
        p = subprocess.run([str(CLI), 'scan', str(path).encode().hex()], capture_output=True, timeout=5)
        assert json.loads(p.stdout)['state'] == 'ERROR', p.stdout
    benign.chmod(0)
    try:
        p = subprocess.run([str(CLI), 'scan', str(benign).encode().hex()], capture_output=True, timeout=5)
        assert json.loads(p.stdout)['state'] == 'ERROR'
    finally:
        benign.chmod(0o600)
    before = snap(benign)
    p = subprocess.Popen([str(CLI), 'scan', str(benign).encode().hex(), '--watch-stdin'],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    time.sleep(.5); p.stdin.close(); p.wait(timeout=5)
    results['cancel'] = json.loads(p.stdout.read()); p.stdout.close()
    assert results['cancel']['state'] == 'INCOMPLETE', results['cancel']
    assert before == snap(benign)
    print(json.dumps(results, indent=2))
