#!/usr/bin/env python3
"""Verify vendored sources; prepare reproducible, offline-only build tree.
Console adaptation: unbuffered guest stdin/stdout so prompts appear over pipes.
The original compiler/parser/linker are unchanged. No source templates involved.
"""
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys
import tarfile
import zlib

ROOT = Path(__file__).resolve().parent


def prepare(destination):
    manifest = json.loads((ROOT / 'upstream/manifest.json').read_text())
    destination = Path(destination)
    for archive, info in manifest.items():
        path = ROOT / 'upstream' / archive
        assert hashlib.sha256(path.read_bytes()).hexdigest() == info['sha256'], archive
        target = destination / ('wcpl' if archive.startswith('wcpl') else 'wamr')
        target.mkdir(parents=True, exist_ok=True)
        with tarfile.open(path) as tar:
            # Archives contain tracked source files only; never follow links.
            assert all(x.isfile() or x.isdir() for x in tar.getmembers())
            tar.extractall(target, filter='data')
    # WCPL embeds its own headers + libc in l.c. Patch ONLY stream default flags
    # in the bundled stdio object (stdin/stdout _IONBF=4), preserving every other
    # byte. Also update source attribution to make the shipped change explicit.
    lib = destination / 'wcpl/l.c'
    text = lib.read_text()
    pattern = r'static unsigned char file_l_41\[13209\] =\n(.*?);'
    match = re.search(pattern, text, re.S)
    assert match
    compressed = bytes(int(x, 8) for x in re.findall(r'\\([0-7]+)', match[1]))
    obj = zlib.decompress(compressed, -15)
    data_pattern = rb'(\(data \$stdio:_iob \(export "_iob"\) var align=4 ")([^"\n]+)(")'
    data_match = re.search(data_pattern, obj)
    assert data_match
    data = bytearray(int(x, 16) for x in re.findall(rb'\\([0-9a-fA-F]{2})', data_match[2]))
    assert data[16] == 1 and data[48] == 2 and data[80] == 6
    data[16] |= 4
    data[48] |= 4
    new_data = ''.join(f'\\{x:02x}' for x in data).encode()
    obj = obj[:data_match.start(2)] + new_data + obj[data_match.end(2):]
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
    packed = compressor.compress(obj) + compressor.flush()
    lines = ['  "' + ''.join(f'\\{x:03o}' for x in packed[i:i+32]) + '"' for i in range(0, len(packed), 32)]
    replacement = f'static unsigned char file_l_41[{len(packed)}] =\n' + '\n'.join(lines) + ';'
    text = text[:match.start()] + replacement + text[match.end():]
    text = text.replace('"lib/stdio.wo", 13209, 1, 192049,', f'"lib/stdio.wo", {len(packed)}, 1, {len(obj)},')
    lib.write_text(text)
    (destination / 'wcpl/lib/stdio.wo').write_bytes(obj)
    src = destination / 'wcpl/lib/src/stdio.c'
    src.write_text(src.read_text().replace('_IOREAD,       0', '_IOREAD|_IONBF, 0').replace('_IOWRT,        1', '_IOWRT|_IONBF,  1'))
    print('PASS: WCPL/WAMR SHA-256; console libc stdin/stdout unbuffered')


if __name__ == '__main__':
    prepare(sys.argv[1])
