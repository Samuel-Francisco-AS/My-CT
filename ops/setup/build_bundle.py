#!/usr/bin/env python3
"""Deterministic, offline APK payload built exclusively from this checkout."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]

def build(destination):
    destination.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT / 'ops/setup/manifest.json').read_text())
    files = {name: (ROOT / name).read_bytes() for name in manifest['sources']}
    manifest['sha256'] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    code = (ROOT / 'ops/setup/setup.py').read_text().replace('MANIFEST = None  # injected by build_bundle.py', 'MANIFEST = ' + repr(manifest))
    addon = (ROOT / 'ops/setup/addons.py').read_text() + '\n' + (ROOT / 'ops/myct_cloud_config.py').read_text() + '\n' + (ROOT / 'ops/setup/cloud.py').read_text() + '\n' + (ROOT / 'ops/setup/recovery.py').read_text()
    code = code.replace('\n\ndef main():', '\n\n' + addon + '\n\ndef main():')
    diagnostic = code.encode()
    (destination / 'diagnose.py').write_bytes(diagnostic)
    with zipfile.ZipFile(destination / 'payload.pyz', 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted({'__main__.py': diagnostic, **files}.items()):
            entry = zipfile.ZipInfo(name, date_time=(2026, 10, 20, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o600 << 16
            archive.writestr(entry, data)
    (destination / 'integrity.json').write_text(json.dumps({
        'release': manifest['release'],
        'payload_sha256': hashlib.sha256((destination / 'payload.pyz').read_bytes()).hexdigest(),
        'diagnose_sha256': hashlib.sha256(diagnostic).hexdigest(),
    }, sort_keys=True) + '\n')

if __name__ == '__main__':
    build(Path(sys.argv[1]))
