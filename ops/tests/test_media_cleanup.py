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

    def test_partial_torrent_storage_is_blocked_without_rpc(self):
        first, src1, dst1 = self.add(1)
        second, src2, dst2 = self.add(2)
        cleanup.core.save_state(self.state)
        req = cleanup.unpack(token('storage', [first]))
        plan = cleanup.preview(req, self.inventory())
        self.assertFalse(plan['can_apply'])
        self.assertTrue(plan['blocked'])
        rpc = FakeRpc()
        with self.assertRaises(cleanup.core.MediaError):
            cleanup.apply(req, plan['fingerprint'], self.rows, rpc)
        self.assertEqual([], rpc.calls)
        self.assertTrue(dst1.exists())
        self.assertTrue(dst2.exists())
        self.assertTrue(src1.exists())

    def test_full_torrent_storage_uses_transmission_rpc(self):
        first, src1, dst1 = self.add(1)
        second, src2, dst2 = self.add(2)
        cleanup.core.save_state(self.state)
        req = cleanup.unpack(token('storage', [first, second]))
        plan = cleanup.preview(req, self.inventory())
        self.assertTrue(plan['can_apply'])
        self.assertEqual(1, len(plan['torrents']))
        rpc = FakeRpc()
        result = cleanup.apply(req, plan['fingerprint'], self.rows, rpc)
        self.assertEqual(1, result['removed_torrents'])
        self.assertEqual(2, result['removed_links'])
        self.assertEqual([('torrent_remove', {'ids': [HASH], 'delete_local_data': True})], rpc.calls)
        self.assertFalse(dst1.exists())
        self.assertFalse(dst2.exists())
        # FakeRpc does not delete payload; that is Transmission's responsibility in production.
        self.assertTrue(src1.exists())
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

    def test_unlinked_extra_video_blocks_storage_deletion(self):
        path, original, dest = self.add(1)
        (self.source_root / 'promo.mp4').write_bytes(b'advert')
        request = cleanup.unpack(token('storage', [path]))
        p = cleanup.preview(request, self.inventory())
        self.assertFalse(p['can_apply'])
        self.assertEqual(1, p['torrents'][0]['videos_outside_library'])
        self.assertTrue(any('vídeos originais' in line for line in p['blocked']))
        rpc = FakeRpc()
        with self.assertRaises(cleanup.core.MediaError):
            cleanup.apply(request, p['fingerprint'], self.rows, rpc)
        self.assertEqual([], rpc.calls)
        self.assertTrue(original.exists())
        self.assertTrue(dest.exists())

    def test_unrelated_links_outside_media_block_reclaim(self):
        path, original, dest = self.add(1)
        other = self.home / 'untracked.mkv'
        os.link(original, other)
        request = cleanup.unpack(token('storage', [path]))
        plan = cleanup.preview(request, self.inventory())
        self.assertFalse(plan['can_apply'])
        self.assertTrue(any('outros hardlinks' in x for x in plan['blocked']))

    def test_invalid_paths_rejected(self):
        for item in ('Series/../../something', '/tmp/not-myct', 'Series'):
            with self.subTest(item=item), self.assertRaises(cleanup.core.MediaError):
                cleanup.unpack(token('links', [item]))

if __name__ == '__main__':
    unittest.main()
