"""CLI contract tests. PATH contains ONLY our fake Rclone; no Drive access."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import importlib.machinery
import importlib.util


SCRIPT = Path(__file__).resolve().parents[1] / 'myct-archive'
loader = importlib.machinery.SourceFileLoader('archive_tests_module', str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
archive = importlib.util.module_from_spec(spec)
loader.exec_module(archive)

FAKE = r'''
import hashlib
import json
import os
from pathlib import Path
import signal
import sys
import time

root = Path(os.environ['FAKE_ROOT'])
args = sys.argv[1:]
with (root / 'calls.jsonl').open('a') as stream:
    stream.write(json.dumps(args) + '\n')
mode = (root / 'mode').read_text()
command = args[0]
assert command in {'copyto', 'lsjson', 'about'}, args
assert '--log-level' in args and '--stats' in args
# The helper must neutralize inherited debug/logging and backend overrides.
assert 'RCLONE_LOG_FILE' not in os.environ
assert 'RCLONE_DUMP' not in os.environ
assert 'RCLONE_DRIVE_IMPORT_FORMATS' not in os.environ

def path(value):
    if ':' in value and not value.startswith('/'):
        remote, relative = value.split(':', 1)
        target = root / 'cloud' / remote / relative
        assert relative == '' or relative.startswith('My-CT-Archive/')
        return target
    return Path(value)

def emit(value):
    print(json.dumps(value))

if mode in {'quota', 'auth', 'network', 'unknown'}:
    message = {'quota': 'storageQuotaExceeded', 'auth': 'invalid_grant',
               'network': 'connection timeout', 'unknown': 'unexpected error'}[mode]
    print(message + ' access_token=SECRET https://oauth.example/SECRET', file=sys.stderr)
    sys.exit(1)
if mode == 'bad_json':
    print('SECRET invalid JSON')
    sys.exit(0)
if command == 'about':
    emit({'total': 4000, 'used': 3000, 'free': 1000} if mode != 'no_quota' else {})
elif command == 'copyto':
    assert '--ignore-existing' in args and '--checksum' in args
    source, dest = path(args[1]), path(args[2])
    dest.parent.mkdir(parents=True, exist_ok=True)
    if mode == 'wait':
        (root / 'ready').write_text(str(os.getpid()))
        time.sleep(30)
    if mode == 'interrupted':
        os.kill(os.getpid(), signal.SIGTERM)
    if not dest.exists():
        content = source.read_bytes()
        if mode == 'partial':
            dest.write_bytes(content[:1])
            print('connection reset SECRET', file=sys.stderr)
            sys.exit(1)
        if mode == 'corrupt_download' and args[1].startswith('myct-gdrive:'):
            content = b'x' * len(content)
        dest.write_bytes(content)
    if mode == 'source_changed':
        source.write_bytes(b'changed during copy')
elif command == 'lsjson':
    directory = path(args[1])
    rows = []
    if directory.exists():
        for item in directory.iterdir():
            content = item.read_bytes()
            rows.append({'Name': item.name, 'Path': item.name, 'IsDir': False,
                         'Size': len(content) + (1 if mode == 'bad_size' else 0),
                         'Hashes': {} if mode == 'no_md5' else {
                             'md5': '0' * 32 if mode == 'bad_md5' else hashlib.md5(content).hexdigest()}})
    if mode == 'duplicate':
        rows *= 2
    if mode == 'missing':
        rows = []
    emit(rows)
'''


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.bin = self.home / 'bin'
        self.bin.mkdir()
        fake = self.bin / 'rclone'
        fake.write_text('#!' + sys.executable + '\n' + FAKE)
        fake.chmod(0o700)
        self.mode('ok')
        self.env = {**os.environ, 'HOME': str(self.home), 'PATH': str(self.bin),
                    'FAKE_ROOT': str(self.home), 'MYCT_ARCHIVE_REMOTE': 'myct-gdrive:',
                    'RCLONE_LOG_FILE': str(self.home / 'forbidden-log'), 'RCLONE_DUMP': 'auth',
                    'RCLONE_DRIVE_IMPORT_FORMATS': 'docx'}
        # Never hand the fake (or a regression) the owner's Rclone config/pass.
        for key in list(self.env):
            if key.startswith('RCLONE_CONFIG'):
                del self.env[key]
        self.source = self.home / 'Arquivo comum á $(touch PWNED); `id`.bin'
        self.source.write_bytes(b'My-CT fixture\x00\xff\n')
        self.original = self.source.read_bytes()
        self.catalog = self.home / '.local/share/myct/archive/catalog.json'

    def mode(self, value):
        (self.home / 'mode').write_text(value)

    def cli(self, *args, error=None):
        result = subprocess.run([sys.executable, str(SCRIPT), *map(str, args)],
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual('', result.stderr)
        self.assertNotIn('SECRET', result.stdout)
        output = json.loads(result.stdout)
        self.assertEqual(0 if error is None else 2, result.returncode, output)
        if error:
            self.assertFalse(output['ok'])
            self.assertEqual(error, output['error']['code'], output)
        else:
            self.assertTrue(output['ok'], output)
        return output

    def archived(self):
        return self.cli('archive', self.source)['entry']

    def entries(self):
        return json.loads(self.catalog.read_text())['entries']

    def calls(self):
        file = self.home / 'calls.jsonl'
        return [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []

    def cloud(self, row):
        return self.home / 'cloud' / row['remote'][:-1] / row['remote_path']

    def test_round_trip_original_preserved_private_catalog_and_restore(self):
        original_stat = self.source.stat()
        row = self.archived()
        self.assertEqual('verified', row['state'])
        self.assertEqual(hashlib.md5(self.original).hexdigest(), row['md5'])
        self.assertEqual(self.original, self.cloud(row).read_bytes())
        listed = self.cli('list')
        self.assertEqual([row], listed['entries'])
        self.assertFalse(listed['remote_checked'])
        restored = self.cli('restore', row['id'])
        target = Path(restored['path'])
        self.assertTrue(target.is_relative_to(self.home / 'Downloads/My-CT/Restored'))
        self.assertEqual(self.original, target.read_bytes())
        self.assertEqual(0o600, target.stat().st_mode & 0o777)
        self.assertEqual(0o600, self.catalog.stat().st_mode & 0o777)
        self.assertEqual(0o700, self.catalog.parent.stat().st_mode & 0o777)
        self.assertEqual('restored', self.entries()[row['id']]['restore']['state'])
        self.assertEqual(self.original, self.source.read_bytes())
        self.assertEqual(original_stat.st_ino, self.source.stat().st_ino)
        self.assertEqual(original_stat.st_mtime_ns, self.source.stat().st_mtime_ns)
        self.assertEqual(self.original, self.cloud(row).read_bytes())
        self.assertFalse((self.home / 'PWNED').exists())
        self.assertFalse((self.home / 'forbidden-log').exists())

    def test_status_reports_effective_quota_and_does_not_invent_it(self):
        self.assertEqual({'total': 4000, 'used': 3000, 'free': 1000}, self.cli('status')['quota_bytes'])
        self.mode('no_quota')
        self.assertEqual({}, self.cli('status')['quota_bytes'])
        self.assertTrue(all(call[0] == 'about' for call in self.calls()))

    def test_repeat_archive_reuses_id_and_existing_copy(self):
        row = self.archived()
        self.assertEqual(row['id'], self.archived()['id'])
        self.assertEqual(1, len(self.entries()))
        self.assertEqual(1, len(list((self.home / 'cloud/myct-gdrive/My-CT-Archive').iterdir())))

    def test_same_name_different_sources_and_changed_content_have_distinct_ids(self):
        first = self.archived()
        other = self.home / 'other' / self.source.name
        other.parent.mkdir()
        other.write_bytes(b'another fixture')
        second = self.cli('archive', other)['entry']
        self.source.write_bytes(b'new version')
        third = self.archived()
        self.assertEqual(3, len({r['id'] for r in (first, second, third)}))
        self.assertEqual(self.original, self.cloud(first).read_bytes())

    def test_empty_file_round_trip(self):
        self.source.write_bytes(b'')
        row = self.archived()
        self.assertEqual(b'', Path(self.cli('restore', row['id'])['path']).read_bytes())

    def test_remote_configurable_and_records_not_redirected_by_current_remote(self):
        row = self.cli('--remote', 'private-drive:', 'archive', self.source)['entry']
        self.assertEqual([], self.cli('list')['entries'])
        self.cli('restore', row['id'], error='NOT_FOUND')
        restored = self.cli('--remote', 'private-drive:', 'restore', row['id'])
        self.assertEqual(self.original, Path(restored['path']).read_bytes())

    def test_bad_remote_and_invalid_usage_never_call_rclone(self):
        for value in ('/tmp/cloud', ':drive:', 'name:path', 'name:../escape', 'token=SECRET:', '--flag:'):
            self.cli('--remote', value, 'status', error='INVALID_REMOTE')
        self.cli('remove', self.source, error='USAGE')
        self.assertEqual([], self.calls())

    def test_missing_rclone_and_offline_list(self):
        (self.bin / 'rclone').rename(self.bin / 'disabled')
        self.cli('status', error='RCLONE_NOT_FOUND')
        self.assertEqual([], self.cli('list')['entries'])

    def test_network_auth_quota_and_unknown_errors_are_sanitized_and_retryable(self):
        for mode, code in [('quota', 'QUOTA'), ('auth', 'AUTH'), ('network', 'NETWORK'), ('unknown', 'RCLONE_ERROR')]:
            with self.subTest(mode=mode):
                self.mode(mode)
                self.cli('archive', self.source, error=code)
                row = next(iter(self.entries().values()))
                self.assertEqual('failed', row['state'])
                self.assertEqual(code, row['error_code'])
                self.assertNotIn('SECRET', self.catalog.read_text())
                self.assertEqual(self.original, self.source.read_bytes())
        self.mode('ok')
        self.assertEqual('verified', self.archived()['state'])
        self.assertEqual(1, len(self.entries()))

    def test_archive_requires_actual_size_and_md5_and_unambiguous_remote(self):
        for mode, code in [('bad_size', 'INTEGRITY'), ('bad_md5', 'INTEGRITY'),
                           ('no_md5', 'VERIFICATION_UNAVAILABLE'), ('duplicate', 'REMOTE_COLLISION'),
                           ('missing', 'REMOTE_COLLISION')]:
            with self.subTest(mode=mode):
                self.mode(mode)
                self.cli('archive', self.source, error=code)
                row = next(iter(self.entries().values()))
                self.assertEqual('unverified' if mode == 'no_md5' else 'failed', row['state'])
                self.cli('restore', row['id'], error='NOT_VERIFIED')
                self.assertEqual(self.original, self.source.read_bytes())
        self.mode('ok')
        self.assertEqual('verified', self.archived()['state'])

    def test_invalid_rclone_json_is_sanitized(self):
        self.mode('bad_json')
        self.cli('status', error='RCLONE_RESPONSE')

    def test_source_change_during_upload_is_not_verified(self):
        self.mode('source_changed')
        self.cli('archive', self.source, error='SOURCE_CHANGED')
        self.assertEqual('failed', next(iter(self.entries().values()))['state'])

    def test_remote_name_collision_never_overwrites_object(self):
        row = self.archived()
        self.cloud(row).write_bytes(b'z' * len(self.original))
        self.cli('archive', self.source, error='INTEGRITY')
        self.assertEqual(b'z' * len(self.original), self.cloud(row).read_bytes())
        self.assertEqual(self.original, self.source.read_bytes())

    def test_partial_upload_is_failed_and_retry_does_not_overwrite_partial(self):
        self.mode('partial')
        self.cli('archive', self.source, error='NETWORK')
        row = next(iter(self.entries().values()))
        self.assertEqual('failed', row['state'])
        self.mode('ok')
        self.cli('archive', self.source, error='INTEGRITY')
        self.assertEqual(self.original[:1], self.cloud(row).read_bytes())
        self.assertEqual(self.original, self.source.read_bytes())

    def test_restore_existing_file_directory_and_symlink_are_never_overwritten(self):
        row = self.archived()
        result = self.cli('restore', row['id'])
        target = Path(result['path'])
        target.write_bytes(b'keep existing')
        self.cli('restore', row['id'], error='DEST_EXISTS')
        self.assertEqual(b'keep existing', target.read_bytes())
        target.unlink()
        target.mkdir()
        self.cli('restore', row['id'], error='DEST_EXISTS')
        target.rmdir()
        target.symlink_to(self.source)
        self.cli('restore', row['id'], error='UNSAFE_PATH')
        self.assertEqual(self.original, self.source.read_bytes())

    def test_restore_corruption_or_partial_download_never_publishes_final(self):
        row = self.archived()
        for mode, code in [('corrupt_download', 'INTEGRITY'), ('partial', 'NETWORK')]:
            with self.subTest(mode=mode):
                self.mode(mode)
                self.cli('restore', row['id'], error=code)
                result = self.entries()[row['id']]['restore']
                self.assertEqual('failed', result['state'])
                self.assertFalse(Path(result['path']).exists())
                self.assertEqual([], list((self.home / 'Downloads/My-CT/Restored').glob('.restore-*')))
        self.mode('ok')
        self.cli('restore', row['id'])

    def test_restore_rechecks_remote_after_archiving(self):
        row = self.archived()
        self.cloud(row).write_bytes(b'damaged')
        self.cli('restore', row['id'], error='INTEGRITY')
        result = self.entries()[row['id']]['restore']
        self.assertFalse(Path(result['path']).exists())
        self.assertEqual('failed', result['state'])

    def test_source_symlink_directory_fifo_and_parent_symlink_refused(self):
        link = self.home / 'link'
        link.symlink_to(self.source)
        self.cli('archive', link, error='UNSAFE_PATH')
        self.cli('archive', self.bin, error='INVALID_FILE')
        fifo = self.home / 'fifo'
        os.mkfifo(fifo)
        self.cli('archive', fifo, error='INVALID_FILE')
        parent = self.home / 'redirect'
        parent.symlink_to(self.home)
        self.cli('archive', parent / self.source.name, error='UNSAFE_PATH')
        self.assertEqual([], self.calls())

    def test_managed_root_symlink_refused(self):
        row = self.archived()
        downloads = self.home / 'Downloads'
        downloads.symlink_to(self.bin)
        self.cli('restore', row['id'], error='UNSAFE_PATH')
        self.assertEqual([], list(self.bin.glob('**/.restore-*')))

    def test_corrupt_catalog_is_preserved_and_blocks_remote_operations(self):
        self.archived()
        calls = len(self.calls())
        for invalid in ('{', '[]', '{"version": 99, "entries": {}}',
                        '{"version": 1, "entries": {"bad": null}}'):
            self.catalog.write_text(invalid)
            self.cli('archive', self.source, error='CATALOG_CORRUPT')
            self.cli('list', error='CATALOG_CORRUPT')
            self.assertEqual(invalid, self.catalog.read_text())
        self.assertEqual(calls, len(self.calls()))

    def test_catalog_traversal_and_redirected_remote_refused(self):
        self.archived()
        original = json.loads(self.catalog.read_text())
        for field, value in [('remote_path', '../escape'), ('name', '../escape'), ('remote', ':drive:')]:
            data = json.loads(json.dumps(original))
            next(iter(data['entries'].values()))[field] = value
            self.catalog.write_text(json.dumps(data))
            self.cli('list', error='CATALOG_CORRUPT')

    def test_catalog_symlink_and_unsafe_permissions_refused(self):
        self.archived()
        self.catalog.chmod(0o644)
        self.cli('list', error='UNSAFE_PATH')
        self.catalog.chmod(0o600)
        self.catalog.unlink()
        self.catalog.symlink_to(self.source)
        self.cli('list', error='UNSAFE_PATH')
        self.assertEqual(self.original, self.source.read_bytes())

    def test_concurrent_operation_returns_busy(self):
        self.cli('list')
        with self.catalog.with_suffix('.lock').open('r+') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            self.cli('archive', self.source, error='BUSY')
        self.assertEqual([], self.calls())

    def test_rclone_process_interruption_is_recorded(self):
        self.mode('interrupted')
        self.cli('archive', self.source, error='INTERRUPTED')
        self.assertEqual('failed', next(iter(self.entries().values()))['state'])

    def test_helper_sigterm_stops_child_and_keeps_original(self):
        self.mode('wait')
        process = subprocess.Popen([sys.executable, str(SCRIPT), 'archive', str(self.source)],
                                   env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 5
            while not (self.home / 'ready').exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue((self.home / 'ready').exists())
            child = int((self.home / 'ready').read_text())
            process.send_signal(signal.SIGTERM)
            output, errors = process.communicate(timeout=7)
            self.assertEqual('', errors)
            self.assertEqual('INTERRUPTED', json.loads(output)['error']['code'])
            self.assertEqual('failed', next(iter(self.entries().values()))['state'])
            with self.assertRaises(ProcessLookupError):
                os.kill(child, 0)
            self.assertEqual(self.original, self.source.read_bytes())
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_atomic_catalog_failure_keeps_previous_catalog(self):
        self.archived()
        previous = self.catalog.read_bytes()
        with patch.object(archive, 'state_dir', return_value=self.catalog.parent), \
                patch.object(archive.os, 'replace', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                archive.save_catalog({'version': 1, 'entries': {}})
        self.assertEqual(previous, self.catalog.read_bytes())
        self.assertEqual([], list(self.catalog.parent.glob('.catalog-*')))

    def test_no_replace_publication_if_target_appears_during_download(self):
        row = self.archived()
        def racing_copy(source, target):
            Path(target).write_bytes(self.original)
            final = archive.restore_root() / row['id'] / row['name']
            final.write_bytes(b'created concurrently')
        with patch.object(archive.Path, 'home', return_value=self.home), \
                patch.object(archive, 'verify_remote'), patch.object(archive, 'copy', side_effect=racing_copy):
            data = archive.load_catalog()
            with self.assertRaises(FileExistsError):
                archive.restore(row['id'], row['remote'], data)
            target = Path(data['entries'][row['id']]['restore']['path'])
            self.assertEqual(b'created concurrently', target.read_bytes())
            self.assertEqual('failed', data['entries'][row['id']]['restore']['state'])

    def test_local_space_exhaustion_prevents_download(self):
        row = self.archived()
        with patch.object(archive.Path, 'home', return_value=self.home), \
                patch.object(archive.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 0})()), \
                patch.object(archive, 'copy') as copy:
            with self.assertRaises(archive.Failure) as caught:
                archive.restore(row['id'], row['remote'], archive.load_catalog())
            self.assertEqual('LOCAL_SPACE', caught.exception.code)
            copy.assert_not_called()

    def test_interrupted_restore_is_recorded_and_does_not_publish(self):
        row = self.archived()
        self.mode('interrupted')
        self.cli('restore', row['id'], error='INTERRUPTED')
        saved = self.entries()[row['id']]
        self.assertEqual('verified', saved['state'])
        self.assertEqual('failed', saved['restore']['state'])
        self.assertFalse(Path(saved['restore']['path']).exists())
        self.assertEqual(self.original, self.cloud(row).read_bytes())


if __name__ == '__main__':
    unittest.main()
