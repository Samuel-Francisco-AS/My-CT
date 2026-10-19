import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

script = Path(__file__).parents[1] / 'myct-media-library'
loader = importlib.machinery.SourceFileLoader('myct_media_library_test', str(script))
spec = importlib.util.spec_from_loader(loader.name, loader)
library = importlib.util.module_from_spec(spec)
loader.exec_module(library)


class LibraryInventoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'Media'

    def create_video(self, relative):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b'video')
        return target

    def test_frieren_year_folder_and_episode_listing(self):
        a = 'Series/Frieren Beyond Journeys End (2023)/Season 01/'
        self.create_video(a + 'Frieren Beyond Journeys End S01E01.mkv')
        self.create_video(a + 'Frieren Beyond Journeys End S01E03.mkv')
        managed = {a + 'Frieren Beyond Journeys End S01E03.mkv': {'id': 'test'}}
        result = library.inspect(self.root, {'links': managed})
        show = result['groups'][0]
        self.assertEqual('anime', show['kind'])
        self.assertEqual('Frieren Beyond Journeys End', show['title'])
        self.assertEqual('Frieren Beyond Journeys End (2023)', show['folder'])
        self.assertEqual('Series/Frieren Beyond Journeys End (2023)', show['path'])
        self.assertEqual(2023, show['year'])
        self.assertEqual([1, 3], show['seasons'][0]['episodes'])
        self.assertEqual([2], show['seasons'][0]['missing'])
        self.assertEqual(1, show['managed'])

    def test_seasons_movies_and_unparsed_are_reported(self):
        self.create_video('Series-TV/Example Show/Season 02/Random filename.mkv')
        self.create_video('Series-TV/Example Show/Season 02/Example Show S02E07.mkv')
        self.create_video('Filmes/Example Movie (2024)/Example Movie (2024).mkv')
        groups = library.inspect(self.root, {'links': {}})['groups']
        show = next(x for x in groups if x['kind'] == 'series')
        movie = next(x for x in groups if x['kind'] == 'movie')
        self.assertEqual(1, show['seasons'][0]['unidentified'])
        self.assertEqual([7], show['seasons'][0]['episodes'])
        self.assertEqual([1, 2, 3, 4, 5, 6], show['seasons'][0]['missing'])
        self.assertEqual('Example Movie', movie['title'])
        self.assertEqual(2024, movie['year'])
        self.assertEqual(1, movie['files'])

    def test_symlinks_are_ignored_and_no_state_is_mutated(self):
        self.create_video('Series/Safe/Season 01/Safe S01E01.mkv')
        external = Path(self.tmp.name) / 'external.mkv'
        external.write_bytes(b'x')
        (self.root / 'Series/Safe/Season 01/ref.mkv').symlink_to(external)
        (self.root / 'Series/shortcut').symlink_to(external.parent, target_is_directory=True)
        first = library.inspect(self.root, {'links': {}})
        self.assertEqual(1, len(first['groups']))
        self.assertEqual(1, first['groups'][0]['files'])
        self.assertEqual(b'x', external.read_bytes())

    def test_empty_and_rejected_library_roots(self):
        self.assertEqual([], library.inspect(self.root, {'links': {}})['groups'])
        elsewhere = Path(self.tmp.name) / 'somewhere'
        elsewhere.mkdir()
        self.root.symlink_to(elsewhere)
        with self.assertRaises(library.core.MediaError):
            library.inspect(self.root, {'links': {}})

if __name__ == '__main__':
    unittest.main()
