"""No real RPC, Rclone, Drive, torrent or library is touched by these tests."""
import base64
import copy
import fcntl
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'myct-archive-torrent'
loader = importlib.machinery.SourceFileLoader('archive_torrent_tests', str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
tool = importlib.util.module_from_spec(spec)
loader.exec_module(tool)


def bencode(value):
    if isinstance(value, int):
        return b'i' + str(value).encode() + b'e'
    if isinstance(value, str):
        value = value.encode()
    if isinstance(value, bytes):
        return str(len(value)).encode() + b':' + value
    if isinstance(value, list):
        return b'l' + b''.join(bencode(x) for x in value) + b'e'
    return b'd' + b''.join(bencode(k) + bencode(v) for k, v in sorted(value.items())) + b'e'


class FakeRpc:
    def __init__(self, row):
        self.row, self.template = row, copy.deepcopy(row)
        self.calls = []
        self.pause_works = True
        self.verify_states = [1, 2, 0]
        self.pending = []
        self.verify_ok = True
        self.failure = None

    def call(self, method, params):
        self.calls.append((method, copy.deepcopy(params)))
        if self.failure == method:
            raise OSError('access_token=SYNTHETIC-SECRET')
        if method == 'torrent_get':
            if self.row is None:
                return {'torrents': []}
            if self.pending:
                self.row['status'] = self.pending.pop(0)
                if self.row['status'] == 0 and not self.verify_ok:
                    self.row.update(have_valid=0, percent_complete=0.0)
                    self.row['files'][0]['bytes_completed'] = 0
            return {'torrents': [copy.deepcopy(self.row)]}
        assert params.get('ids') == [self.template['hash_string']] or method == 'torrent_add'
        if method == 'torrent_stop':
            if self.pause_works:
                self.row['status'] = 0
                self.pending = []
        elif method == 'torrent_start':
            assert tool.complete(self.row), 'Starting incomplete fake would redownload!'
            self.row['status'] = 6
        elif method == 'torrent_set_location':
            assert params['move'] is False
            assert self.row['status'] == 0
            self.row['download_dir'] = params['location']
        elif method == 'torrent_set':
            for field, wanted in [('files_wanted', True), ('files_unwanted', False)]:
                for index in params.get(field, []):
                    self.row['file_stats'][index]['wanted'] = wanted
            for field, priority in [('priority_high', 1), ('priority_low', -1), ('priority_normal', 0)]:
                for index in params.get(field, []):
                    self.row['file_stats'][index]['priority'] = priority
        elif method == 'torrent_verify':
            assert self.row['status'] == 0
            self.pending = list(self.verify_states)
        elif method == 'torrent_add':
            assert params['paused'] is True and 'filename' not in params
            self.row = copy.deepcopy(self.template)
            self.row.update(status=0, download_dir=params['download_dir'])
            return {'torrent_added': {'hash_string': self.row['hash_string'], 'id': 1}}
        else:
            raise AssertionError('Forbidden/unexpected RPC: ' + method)
        return {}


class FakeCloud:
    def __init__(self, root):
        self.root, self.calls = root, []
        self.mode = 'ok'
        self.on_upload = None
        self.free = 10**12

    def path(self, value):
        if value.startswith('/'):
            return Path(value)
        remote, relative = value.split(':', 1)
        assert remote == 'myct-gdrive' and relative.startswith('My-CT-Archive/')
        assert '..' not in Path(relative).parts
        return self.root / relative

    def __call__(self, *args):
        self.calls.append(args)
        action = args[0]
        assert action in {'about', 'mkdir', 'lsjson', 'copyto'}, 'Destructive command!'
        if action == 'about':
            return json.dumps({'free': self.free}).encode()
        if action == 'mkdir':
            self.path(args[1]).mkdir(parents=True, exist_ok=True)
            return b''
        if action == 'copyto':
            assert '--ignore-existing' in args and '--checksum' in args
            assert '--transfers' in args and args[args.index('--transfers') + 1] == '1'
            source, target = self.path(args[1]), self.path(args[2])
            target.parent.mkdir(parents=True, exist_ok=True)
            if self.mode in ('auth', 'network'):
                raise tool.Failure(self.mode.upper(), 'Falha simulada sem detalhes privados.')
            if not target.exists():
                raw = source.read_bytes()
                if self.mode == 'partial':
                    target.write_bytes(raw[:1])
                    raise tool.Failure('NETWORK', 'Falha simulada de rede.')
                target.write_bytes(b'x' * len(raw) if self.mode == 'corrupt' else raw)
            if args[2].startswith('myct-gdrive:') and self.on_upload:
                callback, self.on_upload = self.on_upload, None
                callback()
            return b''
        path = self.path(args[1])
        rows = []
        if path.exists():
            paths = sorted(path.rglob('*') if '--recursive' in args else path.iterdir())
            for item in paths:
                if '--dirs-only' in args and not item.is_dir():
                    continue
                row = {'Path': item.relative_to(path).as_posix(), 'Name': item.name,
                       'IsDir': item.is_dir(), 'Size': -1 if item.is_dir() else item.stat().st_size}
                if item.is_file():
                    row['Hashes'] = {} if self.mode == 'no_md5' else {'md5': hashlib.md5(item.read_bytes()).hexdigest()}
                rows.append(row)
        if self.mode == 'duplicate':
            rows *= 2
        return json.dumps(rows).encode()


class ArchiveTorrentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.addCleanup(patch.stopall)
        patch.object(tool.Path, 'home', return_value=self.home).start()
        self.old_umask = os.umask(0o077)
        self.addCleanup(os.umask, self.old_umask)
        self.contents = {'Pacote/filme.mkv': b'video fixture' * 12,
                         'Pacote/legenda.srt': b'legenda completa\n',
                         'Pacote/extras/leiame.txt': b'extra de teste', 'Pacote/vazio.txt': b''}
        info = {b'name': b'Pacote', b'piece length': 64,
                b'files': [{b'length': len(raw), b'path': [p.encode() for p in name.split('/')[1:]]}
                           for name, raw in self.contents.items()]}
        joined = b''.join(self.contents.values())
        info[b'pieces'] = b''.join(hashlib.sha1(joined[i:i + 64]).digest() for i in range(0, len(joined), 64))
        self.key = hashlib.sha1(bencode(info)).hexdigest()
        self.raw = bencode({b'announce': b'https://example.invalid/SYNTHETIC-SECRET', b'info': info})
        self.base = self.home / 'Downloads/My-CT/Torrents' / self.key
        for name, raw in self.contents.items():
            path = self.base / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
        (self.base / 'Pacote/extra-local.nfo').write_bytes(b'Preservar tambem arquivo fora da lista RPC')
        (self.base / 'Pacote/pasta-vazia').mkdir()
        meta = self.home / '.config/myct-transmission/torrents' / (self.key + '.torrent')
        meta.parent.mkdir(parents=True); meta.write_bytes(self.raw)
        total = sum(map(len, self.contents.values()))
        self.engine = FakeRpc({'id': 1, 'hash_string': self.key, 'name': 'Pacote', 'status': 6,
            'download_dir': str(self.base), 'metadata_percent_complete': 1, 'percent_complete': 1,
            'percent_done': 1, 'total_size': total, 'size_when_done': total, 'left_until_done': 0,
            'have_valid': total, 'have_unchecked': 0, 'error': 0, 'torrent_file': str(meta),
            'files': [{'name': p, 'length': len(raw), 'bytes_completed': len(raw)} for p, raw in self.contents.items()],
            'file_stats': [{'wanted': True, 'priority': 0, 'bytes_completed': len(raw)} for raw in self.contents.values()]})
        self.rpc = tool.Rpc(self.engine)
        self.cloud = FakeCloud(self.home / 'cloud')
        patch.object(tool.common, 'rclone', self.cloud).start()
        patch.dict(os.environ, {'MYCT_ARCHIVE_REMOTE': 'myct-gdrive:'}).start()

    def run_cli(self, *args):
        return tool.run(list(args), self.rpc)

    def archived(self):
        return self.run_cli('archive', self.key)['id']

    def row(self, identity):
        return tool.load_catalog()['entries'][identity]

    def mutations(self):
        return [method for method, _ in self.engine.calls if method != 'torrent_get']

    def assertFailure(self, code, operation, *args):
        with self.assertRaises(tool.Failure) as caught:
            operation(*args)
        self.assertEqual(code, caught.exception.code)
        self.assertNotIn('SYNTHETIC-SECRET', str(caught.exception))

    def test_read_only_complete_multifile_inventory_includes_extras_selection_and_metadata(self):
        self.engine.row['file_stats'][1]['wanted'] = False  # Complete even when unselected.
        result = self.run_cli('inspect', self.key)
        self.assertTrue(result['can_archive'])
        self.assertEqual(4, len(result['torrent_files']))
        self.assertEqual(5, len(result['files']))
        self.assertFalse(result['torrent_files'][1]['wanted'])
        self.assertEqual(['Pacote/extra-local.nfo'], result['extra_files'])
        self.assertTrue(result['metainfo']['available'])
        self.assertEqual(result['total_size'] + len(self.raw), result['storage_bytes_minimum'])
        self.assertGreater(result['total_size'], self.engine.row['total_size'])
        self.assertEqual([], self.mutations()); self.assertEqual([], self.cloud.calls)
        self.assertFalse((self.home / '.local').exists())

    def test_integral_roundtrip_preserves_metadata_manifest_empty_dirs_originals_and_c1a(self):
        c1a = self.home / '.local/share/myct/archive/catalog.json'
        c1a.parent.mkdir(parents=True); c1a.write_text('C1A untouched sentinel')
        before = {p: (f.stat().st_ino, f.read_bytes()) for p, f in tool.tree(self.base)[0].items()}
        identity = self.archived(); row = self.row(identity)
        self.assertEqual('verified', row['state']); self.assertEqual(0, self.engine.row['status'])
        remote = self.cloud.path(tool.remote_folder(row))
        self.assertEqual(self.raw, (remote / 'original.torrent').read_bytes())
        self.assertEqual(row['manifest'], json.loads((remote / 'manifest.json').read_text()))
        self.assertNotIn('SYNTHETIC-SECRET', json.dumps(row))
        result = self.run_cli('restore', identity)
        self.assertEqual('verified', result['state']); self.assertFalse(result['seeding_ready'])
        restored = Path(result['path'])
        self.assertEqual(tool.digest_tree(self.base)[:2], tool.digest_tree(restored)[:2])
        self.assertTrue((restored / 'Pacote/pasta-vazia').is_dir())
        self.assertEqual('C1A untouched sentinel', c1a.read_text())
        self.assertEqual(before, {p: (f.stat().st_ino, f.read_bytes()) for p, f in tool.tree(self.base)[0].items()})
        self.assertEqual(0o600, (tool.common.state_dir() / 'catalog.json').stat().st_mode & 0o777)
        self.assertEqual(0o700, tool.common.state_dir().stat().st_mode & 0o777)
        self.assertEqual({'torrent_stop'}, set(self.mutations()))

    def test_hardlinks_in_library_and_outside_are_reported_and_never_removed(self):
        source = self.base / 'Pacote/filme.mkv'
        link = self.home / 'Media/Filmes/Fixture (2026)/Fixture (2026).mkv'
        link.parent.mkdir(parents=True); os.link(source, link)
        os.link(source, self.home / 'externo.mkv')
        state = {'links': {'Filmes/Fixture (2026)/Fixture (2026).mkv': {
            'id': self.key + ':Pacote/filme.mkv', 'dev': source.stat().st_dev, 'ino': source.stat().st_ino}}, 'ignored': []}
        tool.media.save_state(state)
        result = self.run_cli('inspect', self.key)
        self.assertTrue(result['can_archive']); self.assertEqual(1, len(result['library_links']))
        self.assertEqual(1, result['external_links'][0]['possible_external_links'])
        identity = self.archived(); self.run_cli('restore', identity)
        self.assertEqual(source.stat().st_ino, link.stat().st_ino)
        self.assertEqual(3, source.stat().st_nlink)
        self.assertEqual(state, tool.media.load_state())

    def test_incomplete_missing_metadata_missing_file_and_renamed_paths_block_before_pause(self):
        self.engine.row['have_valid'] -= 1
        self.assertFalse(self.run_cli('inspect', self.key)['can_archive'])
        self.assertFailure('BLOCKED', self.run_cli, 'archive', self.key)
        self.engine.row = copy.deepcopy(self.engine.template)
        meta = Path(self.engine.row['torrent_file']); original = meta.read_bytes(); meta.unlink()
        self.assertFalse(self.run_cli('inspect', self.key)['metainfo']['available'])
        self.assertFailure('BLOCKED', self.run_cli, 'archive', self.key)
        meta.write_bytes(original)
        self.engine.row['files'][0]['name'] = 'renamed.mkv'
        self.assertFailure('BLOCKED', self.run_cli, 'archive', self.key)
        self.engine.row = copy.deepcopy(self.engine.template)
        (self.base / 'Pacote/legenda.srt').unlink()
        self.assertFailure('BLOCKED', self.run_cli, 'archive', self.key)
        self.assertEqual([], self.mutations()); self.assertEqual([], self.cloud.calls)

    def test_symlink_special_file_and_ambiguous_metainfo_are_blocked(self):
        link = self.base / 'unsafe'
        link.symlink_to(self.home)
        self.assertFailure('BLOCKED', self.run_cli, 'archive', self.key)
        link.unlink(); os.mkfifo(link)
        self.assertFailure('BLOCKED', self.run_cli, 'archive', self.key)
        for raw in (b'bad', self.raw + b'garbage', b'd4:infoi1ee'):
            self.assertFailure('META', tool.metainfo, raw, self.key)
        self.assertEqual([], self.mutations())

    def test_pause_is_confirmed_before_any_copy_and_failures_restore_previous_running_state(self):
        original = tool.wait_paused
        with patch.object(tool, 'wait_paused', side_effect=lambda rpc, key: original(rpc, key, timeout=0)):
            self.engine.pause_works = False
            self.assertFailure('PAUSE_TIMEOUT', self.run_cli, 'archive', self.key)
        self.assertFalse(any(call[0] == 'copyto' for call in self.cloud.calls))
        self.engine.pause_works = True
        for mode in ('network', 'auth'):
            self.cloud.mode = mode
            self.assertFailure(mode.upper(), self.run_cli, 'archive', self.key)
            self.assertEqual(6, self.engine.row['status'])
            row = next(iter(tool.load_catalog()['entries'].values()))
            self.assertTrue(row['prior_state_restored'])
            self.assertEqual('failed', row['state'])

    def test_source_modified_during_upload_is_not_verified_or_resumed(self):
        file = self.base / 'Pacote/filme.mkv'
        self.cloud.on_upload = lambda: file.write_bytes(b'changed during copy')
        self.assertFailure('SOURCE_CHANGED', self.run_cli, 'archive', self.key)
        self.assertEqual(0, self.engine.row['status'])
        self.assertNotIn('torrent_start', self.mutations())

    def test_partial_upload_retry_never_overwrites_existing_partial_object(self):
        self.cloud.mode = 'partial'
        self.assertFailure('NETWORK', self.run_cli, 'archive', self.key)
        row = next(iter(tool.load_catalog()['entries'].values()))
        remote_files = list(self.cloud.path(tool.remote_folder(row)).rglob('*'))
        partial = next(p for p in remote_files if p.is_file())
        self.cloud.mode = 'ok'
        self.assertFailure('INTEGRITY', self.run_cli, 'archive', self.key)
        self.assertEqual(1, partial.stat().st_size)

    def test_repetition_reuses_archive_and_restore_without_upload_or_overwrite(self):
        identity = self.archived()
        uploads = len([c for c in self.cloud.calls if c[0] == 'copyto'])
        self.cloud.free = 0
        self.assertEqual(identity, self.archived())
        self.assertEqual(uploads, len([c for c in self.cloud.calls if c[0] == 'copyto']))
        first = self.run_cli('restore', identity)
        self.assertTrue(self.run_cli('restore', identity)['reused'])
        target = Path(first['path']) / 'Pacote/filme.mkv'
        target.write_bytes(b'keep changed target')
        self.assertFailure('INTEGRITY', self.run_cli, 'restore', identity)
        self.assertEqual(b'keep changed target', target.read_bytes())

    def test_remote_md5_absence_duplicates_corruption_and_unowned_id_refused(self):
        identity = self.archived(); row = self.row(identity)
        for mode, code in [('no_md5', 'VERIFICATION_UNAVAILABLE'), ('duplicate', 'REMOTE_COLLISION')]:
            self.cloud.mode = mode
            self.assertFailure(code, self.run_cli, 'restore', identity)
        self.cloud.mode = 'ok'
        remote = self.cloud.path(tool.remote_folder(row)) / 'data/Pacote/legenda.srt'
        remote.write_bytes(b'corrupt')
        self.assertFailure('INTEGRITY', self.run_cli, 'restore', identity)
        row['remote_reserved'] = False
        self.assertFailure('REMOTE_COLLISION', tool.remote_objects, row, True, True)

    def test_insufficient_local_space_blocks_download_and_originals_remain(self):
        identity = self.archived()
        before = len([c for c in self.cloud.calls if c[0] == 'copyto'])
        with patch.object(tool.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 0})()):
            self.assertTrue(self.run_cli('inspect', self.key)['restore_blockers'])
            self.assertFailure('LOCAL_SPACE', self.run_cli, 'restore', identity)
        self.assertEqual(before, len([c for c in self.cloud.calls if c[0] == 'copyto']))
        self.assertTrue(self.base.is_dir())

    def test_corrupt_restore_never_becomes_verified(self):
        identity = self.archived()
        self.cloud.mode = 'corrupt'
        self.assertFailure('INTEGRITY', self.run_cli, 'restore', identity)
        self.assertEqual('failed', self.row(identity)['restore']['state'])
        self.assertNotIn('torrent_start', self.mutations())

    def test_recovery_existing_entry_uses_restored_tree_verifies_then_only_explicitly_resumes(self):
        identity = self.archived(); self.run_cli('restore', identity)
        result = self.run_cli('recover', identity)
        self.assertTrue(result['seeding_ready']); self.assertTrue(result['paused'])
        self.assertEqual(str(tool.restored_path(self.row(identity))), self.engine.row['download_dir'])
        self.assertTrue(self.base.is_dir()); self.assertNotIn('torrent_start', self.mutations())
        naming = tool.torrent.read_naming(self.key)
        self.assertEqual('done', naming['state'])
        self.assertEqual(Path(result.get('path', self.engine.row['download_dir'])).name, naming['target'])
        result = self.run_cli('recover', identity, '--resume')
        self.assertTrue(result['resume_requested']); self.assertEqual(6, result['rpc_status'])
        methods = self.mutations()
        self.assertLess(methods.index('torrent_verify'), methods.index('torrent_start'))
        self.assertEqual(1, methods.count('torrent_start'))

    def test_recovery_reimports_original_metainfo_paused_without_original_entry_or_cache(self):
        self.engine.row['file_stats'][0].update(wanted=False, priority=1)
        identity = self.archived(); self.run_cli('restore', identity)
        Path(self.engine.row['torrent_file']).unlink()
        self.engine.row = None
        result = self.run_cli('recover', identity)
        self.assertEqual('verified', result['state']); self.assertTrue(result['paused'])
        params = next(p for method, p in self.engine.calls if method == 'torrent_add')
        self.assertEqual(self.raw, base64.b64decode(params['metainfo']))
        self.assertTrue(params['paused']); self.assertNotIn('torrent_start', self.mutations())
        self.assertFalse(self.engine.row['file_stats'][0]['wanted'])
        self.assertEqual(1, self.engine.row['file_stats'][0]['priority'])

    def test_recovery_incomplete_divergent_unobserved_or_pending_verification_never_starts(self):
        identity = self.archived(); self.run_cli('restore', identity)
        self.engine.verify_ok = False
        self.assertFailure('VERIFY_INCOMPLETE', self.run_cli, 'recover', identity, '--resume')
        self.assertNotIn('torrent_start', self.mutations())
        self.engine.row = copy.deepcopy(self.engine.template)
        self.engine.verify_ok = True
        for states in ([0], [1] * 20):
            self.engine.verify_states = states
            data = tool.load_catalog()
            self.assertFailure('VERIFY_TIMEOUT', tool.recover, data['entries'][identity], data, self.rpc, True, 0.01)
        self.assertNotIn('torrent_start', self.mutations())
        self.assertEqual(0, self.engine.row['status'])

    def test_rpc_failure_is_sanitized_and_restore_data_tamper_blocks_before_rpc(self):
        self.engine.failure = 'torrent_get'
        self.assertFailure('RPC', self.run_cli, 'inspect', self.key)
        self.engine.failure = None
        identity = self.archived(); self.run_cli('restore', identity)
        file = tool.restored_path(self.row(identity)) / 'Pacote/filme.mkv'
        file.write_bytes(b'not the torrent')
        before = len(self.engine.calls)
        self.assertFailure('INTEGRITY', self.run_cli, 'recover', identity, '--resume')
        self.assertEqual(before, len(self.engine.calls))

    def test_incompatible_existing_entry_is_rejected_without_pausing_it(self):
        identity = self.archived(); self.run_cli('restore', identity)
        self.engine.row.update(download_dir=str(self.home / 'unrelated'), status=6)
        self.engine.calls.clear()
        self.assertFailure('INCOMPATIBLE', self.run_cli, 'recover', identity)
        self.assertEqual([], self.mutations())
        self.assertEqual(6, self.engine.row['status'])

    def test_same_size_corrupt_data_is_blocked_despite_stale_complete_rpc(self):
        file = self.base / 'Pacote/filme.mkv'
        file.write_bytes(b'x' * file.stat().st_size)
        self.assertFailure('PIECES', self.run_cli, 'archive', self.key)
        self.assertEqual([], self.mutations())
        self.assertFalse(any(c[0] == 'copyto' for c in self.cloud.calls))

    def test_unobserved_verification_cannot_use_cached_complete_state(self):
        identity = self.archived(); self.run_cli('restore', identity)
        self.engine.verify_states = [0]
        clock = [0]
        def monotonic():
            clock[0] += 1
            return clock[0]
        with patch.object(tool.time, 'monotonic', side_effect=monotonic), patch.object(tool.time, 'sleep'):
            self.assertFailure('VERIFY_UNOBSERVED', self.run_cli, 'recover', identity, '--resume')
        self.assertNotIn('torrent_start', self.mutations())

    def test_restore_publication_never_replaces_a_concurrent_destination(self):
        identity = self.archived(); row = self.row(identity)
        original_copy = tool.common.copy
        def racing_copy(source, dest):
            original_copy(source, dest)
            if '/data/' in source:
                relative = source.split('/data/', 1)[1]
                target = tool.restored_path(row) / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b'keep concurrent data')
        with patch.object(tool.common, 'copy', side_effect=racing_copy):
            with self.assertRaises(FileExistsError):
                self.run_cli('restore', identity)
        target = tool.restored_path(row) / row['manifest']['files'][0]['path']
        self.assertEqual(b'keep concurrent data', target.read_bytes())
        self.assertEqual('failed', self.row(identity)['restore']['state'])

    def test_preexisting_unowned_archive_id_stays_blocked_on_repetition(self):
        identity = self.archived()
        tool.common.save_catalog({'version': 1, 'entries': {}})
        self.engine.calls.clear()
        for _ in range(2):
            self.assertFailure('REMOTE_COLLISION', self.run_cli, 'archive', self.key)
            self.assertFalse(self.row(identity)['remote_reserved'])
        self.assertEqual([], self.mutations())

    def test_existing_media_lock_blocks_mutations_and_never_starts_copy(self):
        tool.common.private_dir(tool.media.state_path().parent)
        with tool.media.state_path().with_suffix('.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            self.assertFailure('BUSY', self.run_cli, 'archive', self.key)
        self.assertEqual([], self.mutations())
        self.assertEqual(6, self.engine.row['status'])
        self.assertFalse(any(c[0] == 'copyto' for c in self.cloud.calls))

    def test_single_file_metainfo_and_tampered_manifest_paths(self):
        info = {b'name': b'fixture.txt', b'length': 3, b'piece length': 4,
                b'pieces': hashlib.sha1(b'abc').digest()}
        key = hashlib.sha1(bencode(info)).hexdigest()
        self.assertEqual([{'path': 'fixture.txt', 'size': 3}], tool.metainfo(bencode({b'info': info}), key))
        identity = self.archived()
        data = tool.load_catalog()
        data['entries'][identity]['manifest']['files'][0]['path'] = '../escape'
        tool.common.save_catalog(data)
        self.assertFailure('CATALOG', self.run_cli, 'list')

    def test_metadata_symlink_catalog_corruption_and_invalid_cli_preserve_data(self):
        identity = self.archived(); self.run_cli('restore', identity)
        metadata = tool.bundle(self.row(identity)) / 'original.torrent'
        metadata.unlink(); metadata.symlink_to(self.base / 'Pacote/filme.mkv')
        self.assertFailure('UNSAFE_PATH', self.run_cli, 'recover', identity)
        catalog = tool.common.state_dir() / 'catalog.json'
        catalog.write_text('{bad')
        self.assertFailure('CATALOG', self.run_cli, 'list')
        result = subprocess.run([sys.executable, str(SCRIPT), 'remove', self.key],
                                env={**os.environ, 'HOME': str(self.home)}, capture_output=True, text=True)
        self.assertEqual('', result.stderr)
        self.assertEqual('blocked', json.loads(result.stdout)['state'])
        self.assertEqual('USAGE', json.loads(result.stdout)['error']['code'])
        self.assertTrue(self.base.is_dir())

    def test_missing_companion_helpers_return_json_without_network_or_traceback(self):
        script = self.home / 'isolated/myct-archive-torrent'
        script.parent.mkdir(); shutil.copyfile(SCRIPT, script)
        result = subprocess.run([sys.executable, str(script), 'list'],
                                env={**os.environ, 'HOME': str(self.home)}, capture_output=True, text=True)
        self.assertEqual(2, result.returncode); self.assertEqual('', result.stderr)
        self.assertEqual('DEPENDENCIES', json.loads(result.stdout)['error']['code'])
        self.assertEqual([], self.cloud.calls); self.assertEqual([], self.mutations())


if __name__ == '__main__':
    unittest.main()
