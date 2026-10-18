import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

script = Path(__file__).parents[1] / 'myct-media'
loader = importlib.machinery.SourceFileLoader('myct_media_tests', str(script))
spec = importlib.util.spec_from_loader(loader.name, loader)
media = importlib.util.module_from_spec(spec)
loader.exec_module(media)
HASH = 'a' * 40

def make_torrent(home, names, complete=True):
    root = Path(home) / 'Downloads/My-CT/Torrents' / HASH
    root.mkdir(parents=True)
    for filename in names:
        (root / filename).write_bytes(b'fake media content')
    return [{'hash_string': HASH, 'download_dir': str(root), 'left_until_done': 0 if complete else 12,
             'metadata_percent_complete': 1, 'error': 0}]

class MyCtMediaTest(unittest.TestCase):
    def setup_home(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        home = Path(tmp.name)
        p = patch.object(media.Path, 'home', return_value=home)
        p.start(); self.addCleanup(p.stop)
        return home

    def test_recognition_strict_and_no_promos(self):
        self.assertEqual({'kind': 'series', 'title': 'Frieren Beyond Journeys End', 'season':1, 'episode':2},
                         media.classify('Frieren.Beyond.Journeys.End.S01E02.1080p.mkv'))
        self.assertEqual({'kind':'movie', 'title':'Example Film', 'year':2024},
                         media.classify('Example.Film.2024.1080p.WEB.mkv'))
        self.assertIsNone(media.classify('Demon.Slayer.Complete.Season.1080p.mkv'))
        self.assertIsNone(media.classify('1XBET.COM_promo_SHREK_dinheiro_livre.mp4'))
        self.assertIsNone(media.classify('Foo.S01E02E03.mkv'))

    def test_preview_is_read_only_and_incomplete_not_imported(self):
        home = self.setup_home()
        rows = make_torrent(home, ['Frieren.Beyond.Journeys.End.S01E01.1080p.mkv'], complete=False)
        self.assertEqual([], media.run(['scan'], rows)['added'])
        self.assertFalse((home / 'Media').exists())
        rows[0]['left_until_done'] = 0
        self.assertEqual(1, len(media.run(['scan'], rows)['added']))
        self.assertFalse((home / 'Media').exists())

    def test_sync_preserves_original_inode_and_does_not_duplicate(self):
        home = self.setup_home()
        filename = 'Frieren.Beyond.Journeys.End.S01E01.1080p.mkv'
        rows = make_torrent(home, [filename])
        acl = lambda file, root: None
        result = media.run(['sync'], rows, acl=acl)
        self.assertEqual(1, len(result['added']))
        relative = result['added'][0]['target']
        source = home / 'Downloads/My-CT/Torrents' / HASH / filename
        dest = home / 'Media' / relative
        self.assertEqual(source.stat().st_ino, dest.stat().st_ino)
        self.assertEqual(2, source.stat().st_nlink)
        self.assertEqual(1, len(media.run(['sync'], rows, acl=acl)['existing']))
        self.assertEqual(1, len(media.run(['list'])['links']))
        media.run(['remove', relative])
        self.assertTrue(source.exists())
        self.assertFalse(dest.exists())
        self.assertEqual(1, source.stat().st_nlink)
        self.assertEqual(0, len(media.run(['sync'], rows, acl=acl)['added']))
        self.assertEqual(1, media.run(['sync'], rows, acl=acl)['ignored'])

    def test_ambiguous_requires_approval_and_skip(self):
        home = self.setup_home()
        rows = make_torrent(home, ['Obscure.title.mkv', '1xbet_promo.mp4'])
        queue = media.run(['scan'], rows)['pending']
        self.assertEqual(2, len(queue))
        target = [item for item in queue if item['name'] == 'Obscure.title.mkv'][0]
        imported = media.run(['approve', target['id'], 'movie', 'Obscure Film', '2025'], rows, acl=lambda *a: None)
        self.assertIn('added', imported)
        self.assertTrue((home / 'Media' / imported['added']).exists())
        promo = [item for item in queue if item['name'] == '1xbet_promo.mp4'][0]
        media.run(['skip', promo['id']])
        self.assertEqual(1, media.run(['sync'], rows, acl=lambda *a: None)['ignored'])

    def test_collision_never_overwrites_and_symlink_rejected(self):
        home = self.setup_home()
        filename = 'Film.Title.2024.1080p.mkv'
        rows = make_torrent(home, [filename])
        target = 'Filmes/Film Title (2024)/Film Title (2024).mkv'
        destination = home / 'Media' / target
        destination.parent.mkdir(parents=True)
        destination.write_bytes(b'other content')
        preview = media.run(['scan'], rows)
        self.assertEqual(1, len(preview['pending']))
        report = media.run(['sync'], rows, acl=lambda *a: None)
        self.assertEqual(1, len(report['pending']))
        self.assertEqual(b'other content', destination.read_bytes())
        destination.unlink()
        (home / 'Media' / 'Series').symlink_to(home / 'Downloads')
        with self.assertRaises(media.MediaError):
            media.add_one({'source':home / 'Downloads/My-CT/Torrents' / HASH / filename, 'id':'id'},
                          Path('Series/Bad/Season 01/E01.mkv'), {'links': {}, 'ignored': []}, acl=lambda *a: None)

    def test_acl_failure_rolls_back_only_new_link(self):
        home = self.setup_home()
        filename = 'Film.Title.2024.1080p.mkv'
        rows = make_torrent(home, [filename])
        def fail(*args): raise OSError('ACL denied')
        out = media.run(['sync'], rows, acl=fail)
        self.assertEqual(1, len(out['pending']))
        self.assertEqual(1, (home / 'Downloads/My-CT/Torrents' / HASH / filename).stat().st_nlink)
        self.assertEqual({}, media.run(['list'])['links'])

if __name__ == '__main__': unittest.main()
