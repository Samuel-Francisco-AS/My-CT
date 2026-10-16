"""Sparse size checks only: no real 2 GB scan or temp copy is performed."""
import json
import os
from pathlib import Path
import pwd
import subprocess
import tempfile
import unittest

WORKER = Path(__file__).resolve().parents[1] / 'myct-av-scan'
HOME = Path(pwd.getpwuid(os.getuid()).pw_dir)


class AntivirusInputLimits(unittest.TestCase):
    def test_sparse_size_boundaries_before_engine_launch(self):
        with tempfile.TemporaryDirectory(prefix='myct-av-boundary-', dir=HOME) as d:
            p = Path(d) / 'sparse'
            for size, allowed in ((65*1024**2, True), (2000000000, True), (2000000001, False)):
                with self.subTest(size=size):
                    with p.open('wb') as f:
                        f.truncate(size)
                    before = p.stat()
                    env = dict(os.environ); env.pop('MYCT_AV_TMP', None)
                    result = subprocess.run([str(WORKER), str(p).encode().hex()],
                                            capture_output=True, env=env, timeout=3)
                    data = json.loads(result.stdout)
                    # Absence of the internal temp directory aborts AFTER size validation.
                    if allowed:
                        self.assertEqual(data['error'], 'Diretório temporário ausente.')
                    else:
                        self.assertTrue(data['error'].startswith('INCOMPLETE:'))
                    after = p.stat()
                    for attr in ('st_size', 'st_ino', 'st_mode', 'st_uid', 'st_gid',
                                 'st_atime_ns', 'st_mtime_ns', 'st_ctime_ns', 'st_blocks'):
                        self.assertEqual(getattr(before, attr), getattr(after, attr))
