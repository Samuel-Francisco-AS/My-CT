"""Directed telemetry proofs: isolated NDJSON Rclone, no Drive/personal data."""
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from myct_cloud_progress import Progress, read_progress
import test_archive as file_fixture
import test_archive_torrent as torrent_fixture

JOB = '7' * 32
FAKE = r'''
import json,os,sys,time
from pathlib import Path
assert '--use-json-log' in sys.argv
assert sys.argv[sys.argv.index('--stats')+1] == '2s'
source, dest = map(Path, sys.argv[2:4])
size = source.stat().st_size
mode = os.environ.get('PROGRESS_MODE', '')
# Both pipes exceed their buffer capacity; a serial reader would deadlock.
sys.stdout.write('x' * 100000); sys.stdout.flush()
sys.stderr.write('SECRET' * 18000 + '\n'); sys.stderr.flush()
if mode != 'missing':
    for n in (0, size//2, size):
        row = {'stats': {'bytes': n, 'totalBytes': size, 'speed': 0 if mode == 'zero' else 20,
                         'eta': None if mode == 'null' else 4}, 'msg': 'SECRET/path/token'}
        print(json.dumps(row), file=sys.stderr, flush=True)
        if mode == 'pause' and n == size//2:
            Path(os.environ['PROGRESS_READY']).touch()
            time.sleep(.4)
        if mode == 'error' and n == size//2:
            print('connection timeout SECRET', file=sys.stderr); sys.exit(1)
dest.parent.mkdir(parents=True, exist_ok=True)
if not dest.exists(): dest.write_bytes(source.read_bytes())
'''


def receipt(root):
    root.mkdir(parents=True, mode=0o700, exist_ok=True)
    path = root / (JOB + '.json')
    path.write_text(json.dumps({'state': 'running', 'worker_pid': os.getpid()})); path.chmod(0o600)
    return Progress(root, JOB)


class ProgressTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.root = self.home / '.local/share/myct/cloud-jobs'
        self.progress = receipt(self.root)

    def stats(self, n, total=100, speed=20, eta=4):
        self.progress.stats(json.dumps({'stats': {'bytes': n, 'totalBytes': total, 'speed': speed, 'eta': eta}, 'token': 'SECRET'}))

    def test_real_numbers_multifile_and_no_transport_success_before_verification(self):
        p = self.progress; p.plan([100, 100]); p.begin_copy(False)
        self.stats(50); self.assertEqual(p.row['percent'], 25)
        self.stats(100); p.end_copy(); p.begin_copy(False)
        self.stats(50); self.assertEqual(p.row['percent'], 75)
        self.assertEqual(p.row['bytes_done'], 150)
        self.stats(100); p.end_copy()
        self.assertEqual(p.row['phase'], 'verifying'); self.assertIsNone(p.row['percent'])
        self.assertIsNone(p.row['eta_seconds'])
        p.finish(True); self.assertEqual(p.row['percent'], 100)
        self.assertEqual(p.row['phase'], 'done')
        self.assertNotIn('SECRET', (self.root / (JOB + '.progress')).read_text())

    def test_null_eta_zero_speed_invalid_stats_and_hashing(self):
        p = self.progress; p.plan([100]); p.begin_copy(True)
        self.stats(30, eta=None); self.assertIsNone(p.row['eta_seconds'])
        self.stats(40, speed=0); self.assertEqual(p.row['speed_bps'], 0); self.assertIsNone(p.row['eta_seconds'])
        for raw in ('bad', '[]', '{"stats":{"bytes":-1}}', '{"stats":{"bytes":true}}',
                    '{"stats":{"bytes":80,"totalBytes":100,"speed":NaN,"eta":0}}'):
            p.stats(raw)
        self.assertIsNone(p.row['eta_seconds'])
        p.phase('hashing'); self.assertIsNone(p.row['speed_bps']); self.assertIsNone(p.row['eta_seconds'])

    def test_missing_stats_skips_and_failure_never_invent_transmitted_bytes(self):
        p = self.progress; p.plan([100, 100]); p.begin_copy(False); p.end_copy()
        p.begin_copy(False); self.stats(50)
        self.assertEqual(p.row['bytes_done'], 50); self.assertIsNone(p.row['percent'])
        p.finish(False); self.assertEqual(p.row['phase'], 'failed'); self.assertIsNone(p.row['percent'])

    def test_atomic_readers_private_modes_failed_write_and_numeric_projection(self):
        p = self.progress; p.plan([100]); p.begin_copy(False); self.stats(0)
        errors = []; stop = threading.Event()
        def reader():
            while not stop.is_set():
                row = read_progress(self.root, JOB)
                if row is None: errors.append('partial')
        thread = threading.Thread(target=reader); thread.start()
        try:
            for n in range(1, 101): self.stats(n)
        finally:
            stop.set(); thread.join()
        self.assertEqual(errors, [])
        path = self.root / (JOB + '.progress'); before = path.read_bytes()
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.root.stat().st_mode & 0o777, 0o700)
        with patch('myct_cloud_progress.os.replace', side_effect=OSError('disk full')): p.phase('verifying')
        self.assertEqual(before, path.read_bytes()); self.assertEqual([], list(self.root.glob('.progress-*')))
        row = json.loads(before); row['path'] = 'SECRET'; path.write_text(json.dumps(row))
        self.assertNotIn('path', read_progress(self.root, JOB))
        path.chmod(0o644); self.assertIsNone(read_progress(self.root, JOB))

    def test_no_job_no_telemetry_and_invalid_receipt(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(Path, 'home', return_value=self.home):
            p = Progress(); p.plan([100]); p.phase('hashing')
            self.assertFalse(p.enabled)
        (self.root / (JOB + '.json')).write_text('{"state":"succeeded"}')
        self.assertFalse(Progress(self.root, JOB).enabled)


class ProgressEnginesTest(unittest.TestCase):
    def fake(self, home):
        binpath = home / 'progress-bin'; binpath.mkdir()
        script = binpath / 'rclone'; script.write_text('#!' + sys.executable + '\n' + FAKE); script.chmod(0o700)
        patcher = patch.dict(os.environ, {'PATH': str(binpath)})
        patcher.start(); self.addCleanup(patcher.stop)

    def test_single_file_upload_download_use_ndjson(self):
        fixture = file_fixture.ArchiveTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        # Use the existing fake for catalog/hash operations, NDJSON process for copyto.
        original_rclone = file_fixture.archive.rclone
        self.fake(fixture.home)
        p = receipt(fixture.home / '.local/share/myct/cloud-jobs')
        def rclone(*args):
            if args[0] == 'copyto':
                def local(value):
                    return str(fixture.home / 'cloud' / value.replace(':', '/', 1)) if not value.startswith('/') else value
                return original_rclone(args[0], local(args[1]), local(args[2]), *args[3:])
            process = subprocess.run([sys.executable, str(fixture.bin / 'rclone'), *args, '--stats', '0', '--log-level', 'ERROR'],
                                     env={k: v for k, v in fixture.env.items() if not k.startswith('RCLONE_')},
                                     capture_output=True, check=True)
            return process.stdout
        with patch.object(file_fixture.archive.Path, 'home', return_value=fixture.home), \
                patch.object(file_fixture.archive, 'rclone', side_effect=rclone), \
                patch.object(file_fixture.archive, 'progress', p):
            engine = file_fixture.archive
            data = {'version': 1, 'entries': {}}
            engine.private_dir(engine.state_dir())
            row = engine.archive(str(fixture.source), 'myct-gdrive:', data)['entry']
            self.assertEqual(p.row['bytes_done'], len(fixture.original)); self.assertIsNone(p.row['percent'])
            self.assertEqual(p.row['phase'], 'verifying')
            result = engine.restore(row['id'], 'myct-gdrive:', data)
            self.assertEqual(Path(result['path']).read_bytes(), fixture.original)
            self.assertEqual(p.row['bytes_done'], len(fixture.original))
            self.assertNotIn('SECRET', (p.root / (JOB + '.progress')).read_text())

    def test_torrent_multifile_upload_restore_include_metadata_without_hash_bytes(self):
        engine = torrent_fixture.tool
        original_rclone = engine.common.rclone
        fixture = torrent_fixture.ArchiveTorrentTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        self.fake(fixture.home)
        p = receipt(fixture.home / '.local/share/myct/cloud-jobs')
        observed = []
        def rclone(*args):
            if args[0] != 'copyto': return fixture.cloud(*args)
            result = original_rclone(args[0], str(fixture.cloud.path(args[1])), str(fixture.cloud.path(args[2])), *args[3:])
            observed.append(p.row['bytes_done'])
            return result
        with patch.object(engine.common, 'rclone', side_effect=rclone), patch.object(engine.common, 'progress', p):
            identity = fixture.archived(); row = fixture.row(identity)
            expected = sum(v['size'] for v in engine.objects(row).values())
            self.assertEqual(p.row['bytes_total'], expected); self.assertEqual(p.row['bytes_done'], expected)
            self.assertEqual(sorted(observed), observed); self.assertIsNone(p.row['percent'])
            self.assertEqual(p.row['current_file_index'], len(engine.objects(row)))
            observed.clear()
            fixture.run_cli('restore', identity)
            self.assertEqual(p.row['bytes_done'], expected); self.assertEqual(sorted(observed), observed)
            self.assertIsNone(p.row['percent']); self.assertEqual(p.row['phase'], 'verifying')
            p.finish(True); self.assertEqual(p.row['percent'], 100)

    def test_pause_read_new_reader_failure_and_no_deadlock(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary); self.fake(home)
            p = receipt(home / '.local/share/myct/cloud-jobs')
            src = home / 'in'; src.write_bytes(b'x' * 100)
            ready = home / 'ready'
            engine = file_fixture.archive
            with patch.object(engine, 'progress', p), patch.dict(os.environ, {'PROGRESS_MODE': 'pause', 'PROGRESS_READY': str(ready)}):
                p.plan([100])
                thread = threading.Thread(target=engine.copy, args=(str(src), str(home / 'out'))); thread.start()
                deadline = time.monotonic() + 3
                while not ready.exists() and time.monotonic() < deadline: time.sleep(.01)
                self.assertTrue(ready.exists())
                self.assertEqual(read_progress(p.root, JOB)['bytes_done'], 50)
                thread.join(3); self.assertFalse(thread.is_alive())
                self.assertEqual(read_progress(p.root, JOB)['bytes_done'], 100)
                os.environ['PROGRESS_MODE'] = 'error'; p.plan([100])
                with self.assertRaisesRegex(engine.Failure, 'rede'): engine.copy(str(src), str(home / 'error'))
                p.finish(False)
                self.assertEqual(read_progress(p.root, JOB)['bytes_done'], 50)
                self.assertEqual(read_progress(p.root, JOB)['phase'], 'failed')


if __name__ == '__main__':
    unittest.main()
