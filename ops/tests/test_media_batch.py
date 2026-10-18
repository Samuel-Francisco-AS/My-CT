import base64
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

path = Path(__file__).parents[1] / 'myct-media-bulk'
loader = importlib.machinery.SourceFileLoader('media_bulk_tests', str(path))
spec = importlib.util.spec_from_loader(loader.name, loader)
bulk = importlib.util.module_from_spec(spec)
loader.exec_module(bulk)
HASH = 'b' * 40


def token(kind, title, items, season=None, year=None):
    doc = {'version': 1, 'kind': kind, 'title': title, 'files': items}
    if season is not None:
        doc['season'] = season
    if year is not None:
        doc['year'] = year
    return base64.urlsafe_b64encode(json.dumps(doc).encode('utf-8')).decode('ascii').rstrip('=')


class MediaBatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        patched = patch.object(bulk.core.Path, 'home', return_value=self.home)
        patched.start()
        self.addCleanup(patched.stop)

    def fixture(self, filenames):
        folder = self.home / 'Downloads/My-CT/Torrents' / HASH
        folder.mkdir(parents=True, exist_ok=True)
        for name in filenames:
            p = folder / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'test-data')
        rows = [{'hash_string': HASH, 'download_dir': str(folder), 'left_until_done': 0,
                 'metadata_percent_complete': 1, 'error': 0}]
        ids = [HASH + ':' + name for name in filenames]
        return rows, ids

    def test_select_nested_folder_manual_anime_preserves_originals(self):
        names = [f'anime/episode_{i}.mkv' for i in range(1, 4)]
        rows, ids = self.fixture(names)
        listed = bulk.run(['sources'], rows)['files']
        self.assertEqual(names, [x['path'] for x in listed])
        selection = token('anime', 'Nome Arbitrário', [
            {'id': key, 'episode': n} for n, key in enumerate(ids, 1)], season=2)
        planned = bulk.run(['plan', selection], rows)
        self.assertEqual(3, planned['ready'])
        self.assertFalse((self.home / 'Media').exists())
        result = bulk.run(['import', selection], rows, acl=lambda *args: None)
        self.assertEqual(3, result['added'])
        for n, key in enumerate(ids, 1):
            orig = self.home / 'Downloads/My-CT/Torrents' / HASH / names[n-1]
            link = self.home / 'Media/Series/Nome Arbitrário/Season 02' / f'Nome Arbitrário S02E{n:02d}.mkv'
            self.assertEqual(orig.stat().st_ino, link.stat().st_ino)
            self.assertEqual(2, orig.stat().st_nlink)
        self.assertEqual(3, len(bulk.core.run(['list'])['links']))

    def test_general_series_has_distinct_root_and_movies_use_filmes(self):
        rows, ids = self.fixture(['show-abc.mkv', 'film-abc.mkv'])
        s = token('series', 'Drama Qualquer', [{'id': ids[0], 'episode': 7}], season=3)
        self.assertEqual('Series-TV/Drama Qualquer/Season 03/Drama Qualquer S03E07.mkv',
                         bulk.run(['plan', s], rows)['items'][0]['target'])
        f = token('movie', 'Filme de Teste', [{'id': ids[1]}], year=2024)
        self.assertEqual('Filmes/Filme de Teste (2024)/Filme de Teste (2024).mkv',
                         bulk.run(['plan', f], rows)['items'][0]['target'])

    def test_collisions_abort_entire_batch_without_modification(self):
        rows, ids = self.fixture(['Frieren 1.mkv', 'Frieren 2.mkv'])
        target = self.home / 'Media/Series/Frieren/Season 01/Frieren S01E01.mkv'
        target.parent.mkdir(parents=True)
        target.write_bytes(b'existing data')
        spec = token('anime', 'Frieren', [{'id': ids[0], 'episode': 1}, {'id': ids[1], 'episode': 2}], season=1)
        p = bulk.run(['plan', spec], rows)
        self.assertEqual(1, p['collisions'])
        self.assertEqual(1, p['ready'])
        with self.assertRaises(bulk.core.MediaError):
            bulk.run(['import', spec], rows, acl=lambda *args: None)
        self.assertEqual(b'existing data', target.read_bytes())
        self.assertFalse((target.parent / 'Frieren S01E02.mkv').exists())

    def test_non_completed_sources_not_selectable(self):
        rows, ids = self.fixture(['ready.mkv'])
        rows[0]['left_until_done'] = 99
        self.assertEqual([], bulk.run(['sources'], rows)['files'])
        plan = token('movie', 'O Filme', [{'id': ids[0]}], year=2025)
        with self.assertRaises(bulk.core.MediaError):
            bulk.run(['plan', plan], rows)

    def test_invalid_selection_and_duplicate_episode(self):
        rows, ids = self.fixture(['a.mkv', 'b.mkv'])
        invalid = token('anime', 'Test', [{'id': ids[0], 'episode': 1},
                                           {'id': ids[1], 'episode': 1}], season=1)
        with self.assertRaises(bulk.core.MediaError):
            bulk.run(['plan', invalid], rows)
        with self.assertRaises(bulk.core.MediaError):
            bulk.run(['plan', 'abc+&&'], rows)
        too_many = token('movie', 'Film', [{'id': ids[0]}, {'id': ids[1]}], year=2025)
        with self.assertRaises(bulk.core.MediaError):
            bulk.run(['plan', too_many], rows)

if __name__ == '__main__':
    unittest.main()
