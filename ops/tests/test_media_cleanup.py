import base64
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

path = Path(__file__).parents[1] / 'myct-media-cleanup'
loader = importlib.machinery.SourceFileLoader('myct_cleanup_test', str(path))
spec = importlib.util.spec_from_loader(loader.name, loader)
cleanup = importlib.util.module_from_spec(spec)
loader.exec_module(cleanup)
HASH = 'b' * 40

def token(mode, paths):
    doc = {'version': 1, 'mode': mode, 'paths': paths}
    return base64.urlsafe_b64encode(json.dumps(doc).encode()).decode().rstrip('=')

class FakeRpc:
    def __init__(self):
        self.calls = []
    def call(self, name, params):
        self.calls.append((name, params))
        return {}

class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        p = patch.object(cleanup.core.Path, 'home', return_value=self.home)
        p.start()
        self.addCleanup(p.stop)
        self.source_root = self.home / 'Downloads/My-CT/Torrents' / HASH
        self.source_root.mkdir(parents=True)
        self.state = {'links': {}, 'ignored': []}
        self.rows = [{'hash_string': HASH, 'download_dir': str(self.source_root),
                      'left_until_done': 0, 'metadata_percent_complete': 1,
                      'error': 0, 'name': 'Test Complete Torrent', 'status': 6}]

    def add(self, episode, managed=True):
        original = self.source_root / f'Test.Show.S01E{episode:02d}.mkv'
        original.write_bytes(b'video-' + str(episode).encode())
        relative = f'Series/Test Show/Season 01/Test Show S01E{episode:02d}.mkv'
        dest = self.home / 'Media' / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        os.link(original, dest)
        st = dest.stat()
        if managed:
            self.state['links'][relative] = {'id': HASH + ':' + original.name,
                                             'dev': st.st_dev, 'ino': st.st_ino}
        return relative, original, dest

    def inventory(self):
        return cleanup.snapshot(self.rows, self.state)

    def test_unmanaged_hardlinks_can_be_listed_and_selected(self):
        path, src, dest = self.add(1, managed=False)
        items = self.inventory()['files']
        self.assertEqual(1, len(items))
        self.assertFalse(items[0]['managed'])
        self.assertTrue(items[0]['eligible'])
        req = cleanup.unpack(token('links', [path]))
        p = cleanup.preview(req, self.inventory())
        self.assertTrue(p['can_apply'])
        self.assertEqual(1, p['selected'])
        self.assertEqual(0, len(p['torrents']))

    def test_links_mode_preserves_torrent_original_and_records_ignore(self):
        a, src, dest = self.add(1)
        cleanup.core.save_state(self.state)
        req = cleanup.unpack(token('links', [a]))
        plan = cleanup.preview(req, self.inventory())
        outcome = cleanup.apply(req, plan['fingerprint'], self.rows)
        self.assertEqual('links', outcome['mode'])
        self.assertEqual(1, outcome['removed_links'])
        self.assertFalse(dest.exists())
        self.assertTrue(src.is_file())
        self.assertEqual(1, src.stat().st_nlink)
        state = cleanup.core.load_state()
        self.assertEqual({}, state['links'])
        self.assertIn(HASH + ':' + src.name, state['ignored'])

    def test_partial_torrent_storage_preserves_unselected_without_backup(self):
        first, src1, dst1 = self.add(1)
        second, src2, dst2 = self.add(2)
        cleanup.core.save_state(self.state)
        req = cleanup.unpack(token('storage', [first]))
        plan = cleanup.preview(req, self.inventory())
        self.assertTrue(plan['can_apply'])
        self.assertEqual({str(src1), str(dst1)}, {x['path'] for x in plan['targets']})
        rpc = FakeRpc()
        result = cleanup.apply(req, plan['fingerprint'], self.rows, rpc)
        self.assertEqual(2, result['removed_files'])
        self.assertEqual([('torrent_remove', {'ids': [HASH], 'delete_local_data': False})], rpc.calls)
        self.assertFalse(src1.exists())
        self.assertFalse(dst1.exists())
        self.assertTrue(src2.exists())
        self.assertTrue(dst2.exists())
        self.assertIn(second, cleanup.core.load_state()['links'])

    def test_full_torrent_storage_only_unlinks_previewed_files(self):
        first, src1, dst1 = self.add(1)
        second, src2, dst2 = self.add(2)
        cleanup.core.save_state(self.state)
        req = cleanup.unpack(token('storage', [first, second]))
        plan = cleanup.preview(req, self.inventory())
        rpc = FakeRpc()
        result = cleanup.apply(req, plan['fingerprint'], self.rows, rpc)
        self.assertEqual(1, result['removed_torrents'])
        self.assertEqual(4, result['removed_files'])
        self.assertTrue(all(not p.exists() for p in (src1, src2, dst1, dst2)))
        self.assertEqual({}, cleanup.core.load_state()['links'])

    def test_changed_link_rejects_old_fingerprint(self):
        path, original, dest = self.add(1)
        cleanup.core.save_state(self.state)
        req = cleanup.unpack(token('storage', [path]))
        prior = cleanup.preview(req, self.inventory())
        dest.unlink()
        dest.write_bytes(b'replaced')
        rpc = FakeRpc()
        with self.assertRaises(cleanup.core.MediaError):
            cleanup.apply(req, prior['fingerprint'], self.rows, rpc)
        self.assertEqual([], rpc.calls)
        self.assertTrue(original.exists())

    def test_extra_video_and_other_hardlinks_do_not_block(self):
        path, original, dest = self.add(1)
        extra = self.source_root / 'promo.mp4'
        extra.write_bytes(b'advert')
        other = self.home / 'untracked.mkv'
        os.link(original, other)
        cleanup.core.save_state(self.state)
        request = cleanup.unpack(token('storage', [path]))
        plan = cleanup.preview(request, self.inventory())
        self.assertTrue(plan['can_apply'])
        cleanup.apply(request, plan['fingerprint'], self.rows, FakeRpc())
        self.assertTrue(extra.exists())
        self.assertEqual(b'video-1', other.read_bytes())

    def test_independent_unmanaged_file_is_selectable_without_torrent(self):
        path, src, dest = self.add(1, managed=False)
        dest.unlink(); dest.write_bytes(b'independent')
        cleanup.core.save_state(self.state)
        request = cleanup.unpack(token('storage', [path]))
        plan = cleanup.preview(request, self.inventory())
        self.assertEqual([str(dest)], [x['path'] for x in plan['targets']])
        cleanup.apply(request, plan['fingerprint'], self.rows, FakeRpc())
        self.assertTrue(src.exists()); self.assertFalse(dest.exists())

    def test_partial_or_error_torrent_is_not_a_deletion_gate(self):
        path, src, dest = self.add(1)
        self.rows[0].update(left_until_done=999, error=1)
        cleanup.core.save_state(self.state)
        request = cleanup.unpack(token('storage', [path]))
        plan = cleanup.preview(request, self.inventory())
        self.assertTrue(plan['can_apply'])
        cleanup.apply(request, plan['fingerprint'], self.rows, FakeRpc())
        self.assertFalse(src.exists()); self.assertFalse(dest.exists())

    def test_rpc_failure_preserves_all_files(self):
        path, src, dest = self.add(1)
        cleanup.core.save_state(self.state)
        request = cleanup.unpack(token('storage', [path]))
        plan = cleanup.preview(request, self.inventory())
        rpc = FakeRpc()
        with patch.object(rpc, 'call', side_effect=OSError('ambiguous')):
            with self.assertRaises(OSError): cleanup.apply(request, plan['fingerprint'], self.rows, rpc)
        self.assertTrue(src.exists()); self.assertTrue(dest.exists())

    def test_symlink_replacement_and_same_inode_content_change_revoke_confirmation(self):
        path, src, dest = self.add(1)
        cleanup.core.save_state(self.state)
        request = cleanup.unpack(token('storage', [path]))
        plan = cleanup.preview(request, self.inventory())
        src.write_bytes(b'changed-size')
        with self.assertRaises(cleanup.core.MediaError):
            cleanup.apply(request, plan['fingerprint'], self.rows, FakeRpc())
        plan = cleanup.preview(request, self.inventory())
        dest.unlink(); dest.symlink_to(src)
        with self.assertRaises(cleanup.core.MediaError):
            cleanup.apply(request, plan['fingerprint'], self.rows, FakeRpc())
        self.assertTrue(src.exists())

    def test_change_during_transmission_removal_aborts_before_unlink(self):
        path, src, dest = self.add(1)
        cleanup.core.save_state(self.state)
        request = cleanup.unpack(token('storage', [path]))
        plan = cleanup.preview(request, self.inventory())
        rpc = FakeRpc()
        def mutate(*args): src.write_bytes(b'changed while RPC pending')
        with patch.object(rpc, 'call', side_effect=mutate):
            with self.assertRaises(cleanup.core.MediaError):
                cleanup.apply(request, plan['fingerprint'], self.rows, rpc)
        self.assertTrue(src.exists()); self.assertTrue(dest.exists())

    def test_busy_lock_preserves_selection_and_catalog(self):
        path, src, dest = self.add(1)
        cleanup.core.save_state(self.state)
        request = cleanup.unpack(token('storage', [path]))
        plan = cleanup.preview(request, self.inventory())
        before = cleanup.core.state_path().read_bytes()
        with cleanup.reconcile.locks(cleanup.core, [HASH]):
            with self.assertRaises(cleanup.core.MediaError):
                cleanup.apply(request, plan['fingerprint'], self.rows, FakeRpc())
        self.assertEqual(before, cleanup.core.state_path().read_bytes())
        self.assertTrue(src.exists()); self.assertTrue(dest.exists())

    def test_incomplete_unlink_updates_catalog_for_completed_library_removal(self):
        first, src1, dst1 = self.add(1)
        second, src2, dst2 = self.add(2)
        cleanup.core.save_state(self.state)
        request = cleanup.unpack(token('links', [first, second]))
        plan = cleanup.preview(request, self.inventory())
        original = os.unlink
        def fail_second(path, *args, **kwargs):
            if path == dst2.name: raise OSError('fixture disk error')
            return original(path, *args, **kwargs)
        with patch.object(cleanup.os, 'unlink', side_effect=fail_second):
            with self.assertRaises(OSError): cleanup.apply(request, plan['fingerprint'], self.rows)
        self.assertFalse(dst1.exists()); self.assertTrue(dst2.exists())
        self.assertNotIn(first, cleanup.core.load_state()['links'])
        self.assertIn(second, cleanup.core.load_state()['links'])
        self.assertTrue(src1.exists()); self.assertTrue(src2.exists())

    def test_invalid_paths_rejected(self):
        for item in ('Series/../../something', '/tmp/not-myct', 'Series'):
            with self.subTest(item=item), self.assertRaises(cleanup.core.MediaError):
                cleanup.unpack(token('links', [item]))

if __name__ == '__main__':
    unittest.main()
