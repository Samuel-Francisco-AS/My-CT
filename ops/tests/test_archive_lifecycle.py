"""C1B-2: fixtures only; no installed helper, real RPC, cloud or library."""
import copy
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

import test_archive_torrent as fixtures

tool = fixtures.tool


class LifecycleTest(unittest.TestCase):
    run_cli = fixtures.ArchiveTorrentTest.run_cli
    archived = fixtures.ArchiveTorrentTest.archived
    row = fixtures.ArchiveTorrentTest.row
    mutations = fixtures.ArchiveTorrentTest.mutations
    assertFailure = fixtures.ArchiveTorrentTest.assertFailure

    def setUp(self):
        fixtures.ArchiveTorrentTest.setUp(self)
        original = self.engine.call
        def call(method, params):
            if method == 'torrent_remove':
                self.engine.calls.append((method, copy.deepcopy(params)))
                assert params == {'ids': [self.key], 'delete_local_data': False}
                if self.engine.failure == method:
                    self.engine.row = None  # Accepted, but response lost: ambiguous.
                    raise TimeoutError('SYNTHETIC-SECRET')
                self.engine.row = None
                return {}
            return original(method, params)
        self.engine.call = call

    def linked(self):
        self.rel = 'Filmes/Fixture (2026)/Fixture (2026).mkv'
        self.link = self.home / 'Media' / self.rel
        source = self.base / 'Pacote/filme.mkv'
        self.link.parent.mkdir(parents=True)
        os.link(source, self.link)
        tool.media.save_state({'links': {self.rel: {'id': self.key + ':Pacote/filme.mkv',
            'dev': source.stat().st_dev, 'ino': source.stat().st_ino}}, 'ignored': []})

    def prepare(self, library=True, restored=True):
        if library:
            self.linked()
        identity = self.archived()
        if restored:
            self.run_cli('restore', identity)
            self.run_cli('recover', identity)
        self.engine.calls.clear(); self.cloud.calls.clear()
        return identity

    def preview(self, identity, remove_library=False):
        return self.run_cli('space', identity, *(['--remove-library', self.rel] if remove_library else []))

    def apply(self, identity, plan, remove_library=False, **overrides):
        options = {'--preview': plan['preview'], '--confirm': plan['preview']}
        if remove_library:
            options.update({'--remove-library': self.rel, '--confirm-library': plan['preview']})
        options.update(overrides)
        return self.run_cli('release', identity, *[s for pair in options.items() for s in pair])

    def test_read_only_inventory_counts_distinct_inodes_all_files_extras_and_retained_library(self):
        identity = self.prepare()
        def metadata(p):
            s = p.stat()
            # Reading can update atime under relatime; it is not a content/state mutation.
            return (s.st_dev, s.st_ino, s.st_nlink, s.st_size, s.st_mode,
                    s.st_uid, s.st_gid, s.st_mtime_ns, s.st_ctime_ns)
        before = {p: metadata(p) for root in [self.base, tool.restored_path(self.row(identity))]
                  for p in root.rglob('*')}
        catalog = tool.common.state_dir() / 'catalog.json'
        catalog_before = (catalog.read_bytes(), catalog.stat().st_mtime_ns)
        state_before = tool.media.state_path().read_bytes()
        plan = self.preview(identity)
        self.assertTrue(plan['can_release'], plan['blockers'])
        self.assertEqual(2, len(plan['trees']))
        self.assertEqual(10, len(plan['inodes']))  # Five files x two independent trees.
        self.assertEqual(10, len(plan['remove_paths']))
        shared = next(i for i in plan['inodes'] if i['nlink'] == 2)
        self.assertEqual(2, len(shared['known_paths']))
        self.assertEqual(0, shared['reclaim_candidate_bytes'])
        self.assertEqual(shared['physical_bytes'], plan['retained_known_bytes'])
        self.assertEqual(plan['physical_bytes'] - shared['physical_bytes'], plan['reclaim_estimate_bytes'])
        self.assertEqual(0, plan['reclaim_guaranteed_bytes'])
        self.assertIn('Pacote/extra-local.nfo', [p['source'] for p in plan['remove_paths']])
        self.assertEqual([], self.mutations())
        self.assertTrue(all(c[0] == 'lsjson' for c in self.cloud.calls))
        self.assertEqual(catalog_before, (catalog.read_bytes(), catalog.stat().st_mtime_ns))
        self.assertEqual(state_before, tool.media.state_path().read_bytes())
        self.assertEqual(before, {p: metadata(p) for p in before})

    def test_preview_does_not_create_missing_bundle_or_locks(self):
        identity = self.prepare(False, False)
        local = tool.bundle(self.row(identity))
        fixtures.shutil.rmtree(local)  # This test's synthetic metadata only.
        lock = tool.media.state_path().with_suffix('.lock')
        lock.unlink()
        plan = self.preview(identity)
        self.assertFalse(plan['can_release']); self.assertFalse(local.exists()); self.assertFalse(lock.exists())
        self.assertEqual([], self.mutations())

    def test_invalid_confirmation_selection_changed_and_expired_preview_block_before_rpc(self):
        identity = self.prepare()
        plan = self.preview(identity)
        self.assertFailure('CONFIRMATION', lambda: self.apply(identity, plan, **{'--confirm': 'bad'}))
        self.assertFailure('STALE_PREVIEW', self.apply, identity, plan, True)
        with patch.object(tool.time, 'time', return_value=int(plan['preview'].split('-')[0]) + 601):
            self.assertFailure('STALE_PREVIEW', self.apply, identity, plan)
        self.assertEqual([], self.mutations()); self.assertTrue(self.base.exists()); self.assertTrue(self.link.exists())

    def test_changed_inode_directory_content_or_rpc_status_blocks_stale_preview(self):
        identity = self.prepare()
        plan = self.preview(identity)
        self.engine.row['status'] = 6
        self.assertFailure('STALE_PREVIEW', self.apply, identity, plan)
        plan = self.preview(identity)
        source = self.base / 'Pacote/legenda.srt'
        raw = source.read_bytes(); source.unlink(); source.write_bytes(raw)
        self.assertFailure('STALE_PREVIEW', self.apply, identity, plan)
        plan = self.preview(identity)
        (self.base / 'Pacote/new.txt').write_bytes(b'not archived')
        self.assertFailure('BLOCKED', self.apply, identity, plan)
        self.assertEqual([], self.mutations())

    def test_remote_bad_missing_md5_network_auth_manifest_and_metainfo_block_release(self):
        identity = self.prepare()
        plan = self.preview(identity)
        for mode in ('no_md5', 'duplicate'):
            self.cloud.mode = mode
            self.assertFalse(self.preview(identity)['can_release'])
            self.assertFailure('BLOCKED', self.apply, identity, plan)
        self.cloud.mode = 'ok'
        for code in ('NETWORK', 'AUTH'):
            with patch.object(tool.common, 'rclone', side_effect=tool.Failure(code, 'Falha sanitizada.')):
                self.assertIn(code, self.preview(identity)['blockers'])
                self.assertFailure('BLOCKED', self.apply, identity, plan)
        remote = self.cloud.path(tool.remote_folder(self.row(identity)))
        for name in ('manifest.json', 'original.torrent', 'data/Pacote/legenda.srt'):
            file = remote / name; raw = file.read_bytes(); file.write_bytes(b'x' * len(raw))
            self.assertIn('INTEGRITY', self.preview(identity)['blockers'])
            self.assertFailure('BLOCKED', self.apply, identity, plan)
            file.write_bytes(raw)
        self.assertEqual([], self.mutations()); self.assertTrue(self.base.exists())

    def test_unknown_hardlinks_block_even_when_library_is_preserved(self):
        identity = self.prepare()
        os.link(self.base / 'Pacote/filme.mkv', self.home / 'external.mkv')
        plan = self.preview(identity)
        self.assertIn('UNKNOWN_LINKS', plan['blockers'])
        self.assertEqual(1, next(i for i in plan['inodes'] if i['unknown_links'])['unknown_links'])
        self.assertFailure('BLOCKED', self.apply, identity, plan)
        self.assertEqual([], self.mutations())

    def test_default_release_preserves_library_and_repeated_apply_never_repeats_mutations(self):
        identity = self.prepare()
        row = self.row(identity); restored = tool.restored_path(row)
        inode = self.link.stat().st_ino; raw = self.link.read_bytes()
        plan = self.preview(identity)
        result = self.apply(identity, plan)
        self.assertEqual('released', result['state'])
        self.assertFalse(self.base.exists()); self.assertFalse(restored.exists())
        self.assertEqual((inode, raw), (self.link.stat().st_ino, self.link.read_bytes()))
        self.assertIsNone(self.engine.row)
        self.assertEqual(['torrent_stop', 'torrent_remove'], self.mutations())
        self.assertEqual(self.row(identity)['manifest'], row['manifest'])
        self.assertTrue(tool.local_bundle_verified(row))
        tool.remote_objects(row)
        calls = len(self.mutations())
        self.assertTrue(self.apply(identity, plan)['already_released'])
        self.assertEqual(calls, len(self.mutations()))
        after = self.preview(identity)
        self.assertEqual(self.link.stat().st_blocks * 512, after['retained_known_bytes'])
        self.assertFalse(after['can_release'])

    def test_full_release_restore_reimport_relink_and_explicit_seed_start(self):
        identity = self.prepare()
        plan = self.preview(identity, True)
        self.assertEqual(0, plan['retained_known_bytes'])
        self.assertEqual(plan['physical_bytes'], plan['reclaim_estimate_bytes'])
        self.assertFailure('LIBRARY_CONFIRMATION', lambda: self.apply(identity, plan, True, **{'--confirm-library': 'bad'}))
        result = self.apply(identity, plan, True)
        self.assertEqual('released', result['state']); self.assertFalse(self.link.exists())
        self.assertNotIn('torrent_start', self.mutations())
        self.run_cli('restore', identity)
        self.run_cli('recover', identity)
        self.assertTrue(next(p for m, p in self.engine.calls if m == 'torrent_add')['paused'])
        row = self.row(identity)
        acl_calls = []
        with patch.object(tool.media, 'ensure_access', side_effect=lambda *a: acl_calls.append(a)):
            result = self.run_cli('relink', identity, '--library', self.rel)
            self.assertEqual('added', result['library'][0]['outcome'])
            self.assertEqual('existing', self.run_cli('relink', identity, '--library', self.rel)['library'][0]['outcome'])
        self.assertEqual(1, len(acl_calls))
        restored = tool.restored_path(row)
        self.assertEqual((restored / 'Pacote/filme.mkv').stat().st_ino, self.link.stat().st_ino)
        self.assertEqual(self.contents['Pacote/legenda.srt'], (restored / 'Pacote/legenda.srt').read_bytes())
        self.assertTrue((restored / 'Pacote/extra-local.nfo').exists())
        self.assertTrue((restored / 'Pacote/pasta-vazia').is_dir())
        self.assertNotIn('torrent_start', self.mutations())
        result = self.run_cli('recover', identity, '--resume')
        self.assertTrue(result['seeding_ready']); self.assertTrue(result['seed_started'])
        self.assertFalse(result['upload_observed']); self.assertEqual(6, result['rpc_status'])
        self.assertEqual(1, self.mutations().count('torrent_start'))
        self.assertTrue(self.run_cli('restore', identity)['reused'])

    def test_preserved_library_can_be_released_only_after_new_selection_and_double_consent(self):
        identity = self.prepare()
        first = self.preview(identity)
        self.apply(identity, first)
        self.assertTrue(self.link.exists())
        self.engine.calls.clear()
        second = self.preview(identity, True)
        self.assertTrue(second['can_release'], second['blockers'])
        self.assertEqual([str(self.link)], [p['path'] for p in second['remove_paths']])
        self.assertEqual([], second['directories'])
        self.assertEqual(self.link.stat().st_blocks * 512, second['reclaim_estimate_bytes'])
        self.assertFailure('LIBRARY_CONFIRMATION', lambda: self.run_cli('release', identity,
            '--preview', second['preview'], '--confirm', second['preview'], '--remove-library', self.rel))
        self.apply(identity, second, True)
        self.assertFalse(self.link.exists()); self.assertEqual([], self.mutations())
        self.run_cli('restore', identity); self.run_cli('recover', identity)
        with patch.object(tool.media, 'ensure_access'):
            self.assertEqual('added', self.run_cli('relink', identity, '--library', self.rel)['library'][0]['outcome'])

    def test_relink_respects_ignored_and_divergent_destination_and_requires_paused_recovery(self):
        identity = self.prepare()
        self.assertFailure('DEST_EXISTS', self.run_cli, 'relink', identity, '--library', self.rel)
        state = tool.media.load_state(); state['ignored'].append(self.key + ':Pacote/filme.mkv'); tool.media.save_state(state)
        result = self.run_cli('relink', identity, '--library', self.rel)
        self.assertEqual([self.rel], result['ignored']); self.assertEqual([], result['library'])
        self.assertEqual(self.base.joinpath('Pacote/filme.mkv').stat().st_ino, self.link.stat().st_ino)
        self.engine.row['status'] = 6
        self.assertFailure('RECOVERY_REQUIRED', self.run_cli, 'relink', identity, '--library', self.rel)

    def test_rpc_timeout_after_remove_never_unlinks_or_repeats_and_blocks_recover_restore(self):
        identity = self.prepare()
        plan = self.preview(identity)
        self.engine.failure = 'torrent_remove'
        self.assertFailure('RELEASE_UNCERTAIN', self.apply, identity, plan)
        self.assertTrue(self.base.exists()); self.assertTrue(self.link.exists())
        self.assertEqual('uncertain', self.row(identity)['release']['state'])
        for action in (lambda: self.apply(identity, plan), lambda: self.run_cli('restore', identity),
                       lambda: self.run_cli('recover', identity)):
            self.assertFailure('RELEASE_UNCERTAIN', action)
        self.assertEqual(1, self.mutations().count('torrent_remove'))
        self.assertIn('RELEASE_UNCERTAIN', self.preview(identity)['blockers'])

    def test_stop_timeout_and_partial_unlink_failures_are_journaled_and_never_replayed(self):
        identity = self.prepare()
        plan = self.preview(identity)
        self.engine.failure = 'torrent_stop'
        self.assertFailure('RELEASE_UNCERTAIN', self.apply, identity, plan)
        self.assertNotIn('torrent_remove', self.mutations()); self.assertTrue(self.base.exists())

    def test_running_seeder_is_stopped_and_confirmed_before_removing_entry_or_files(self):
        identity = self.prepare()
        self.engine.row['status'] = 6
        original = self.engine.call
        observed = []
        def call(method, params):
            if method == 'torrent_remove':
                observed.append(self.engine.row['status'])
                self.assertTrue(self.base.exists()); self.assertTrue(self.link.exists())
            return original(method, params)
        with patch.object(self.engine, 'call', side_effect=call):
            plan = self.preview(identity)
            self.apply(identity, plan)
        self.assertEqual([0], observed)
        self.assertEqual(['torrent_stop', 'torrent_remove'], self.mutations())

    def test_pause_not_observed_prevents_entry_removal_and_all_unlinks(self):
        identity = self.prepare()
        self.engine.row['status'] = 6; self.engine.pause_works = False
        plan = self.preview(identity)
        original = tool.wait_paused
        with patch.object(tool, 'wait_paused', side_effect=lambda rpc, key: original(rpc, key, 0)):
            self.assertFailure('RELEASE_UNCERTAIN', self.apply, identity, plan)
        self.assertEqual(['torrent_stop'], self.mutations()); self.assertTrue(self.base.exists())

    def test_partial_unlink_failure_preserves_journal_remaining_files_and_remote(self):
        identity = self.prepare()
        plan = self.preview(identity)
        original = tool.os.unlink
        calls = []
        def unlink(path, *a, **kw):
            if kw.get('dir_fd') is not None:
                calls.append(path)
                if len(calls) == 2:
                    raise OSError('synthetic filesystem failure')
            return original(path, *a, **kw)
        with patch.object(tool.os, 'unlink', side_effect=unlink):
            self.assertFailure('RELEASE_UNCERTAIN', self.apply, identity, plan)
        journal = self.row(identity)['release']
        self.assertEqual(1, len(journal['removed'])); self.assertEqual('uncertain', journal['state'])
        self.assertTrue(self.link.exists()); tool.remote_objects(self.row(identity))
        self.assertFailure('RELEASE_UNCERTAIN', self.apply, identity, plan)

    def test_concurrent_reimport_after_first_unlink_stops_further_removals(self):
        identity = self.prepare()
        plan = self.preview(identity)
        original = tool.os.unlink
        count = []
        def unlink(path, *a, **kw):
            result = original(path, *a, **kw)
            if kw.get('dir_fd') is not None:
                count.append(path)
                self.engine.row = copy.deepcopy(self.engine.template)
            return result
        with patch.object(tool.os, 'unlink', side_effect=unlink):
            self.assertFailure('RELEASE_UNCERTAIN', self.apply, identity, plan)
        self.assertEqual(1, len(count))
        self.assertEqual(1, len(self.row(identity)['release']['removed']))
        self.assertTrue(self.link.exists())

    def test_corrupt_release_journal_is_preserved_and_blocks_all_followup_actions(self):
        identity = self.prepare()
        data = tool.load_catalog()
        data['entries'][identity]['release'] = {'state': 'invalid'}
        tool.common.save_catalog(data)
        self.assertFailure('CATALOG', self.run_cli, 'space', identity)
        self.assertFailure('CATALOG', self.run_cli, 'restore', identity)
        self.assertEqual([], self.mutations()); self.assertTrue(self.base.exists())

    def test_torrent_already_removed_can_release_without_stop_or_remove_rpc(self):
        identity = self.prepare(False, False)
        self.engine.row = None
        plan = self.preview(identity)
        self.assertTrue(plan['can_release'], plan['blockers'])
        self.apply(identity, plan)
        self.assertEqual([], self.mutations()); self.assertFalse(self.base.exists())

    def test_shared_rpc_tree_and_incomplete_torrent_block_before_mutation(self):
        identity = self.prepare()
        original = self.engine.call
        def shared(method, params):
            result = original(method, params)
            if method == 'torrent_get' and 'ids' not in params:
                other = copy.deepcopy(result['torrents'][0]); other['hash_string'] = 'a' * 40
                result['torrents'].append(other)
            return result
        with patch.object(self.engine, 'call', side_effect=shared):
            plan = self.preview(identity)
            self.assertIn('SHARED_TORRENT', plan['blockers'])
            self.assertFailure('BLOCKED', self.apply, identity, plan)
        self.engine.row['have_unchecked'] = 1
        self.assertIn('TORRENT_STATE', self.preview(identity)['blockers'])
        self.assertEqual([], self.mutations())

    def test_state_changes_during_rpc_block_before_first_unlink(self):
        identity = self.prepare()
        plan = self.preview(identity)
        original = self.engine.call
        def changed(method, params):
            result = original(method, params)
            if method == 'torrent_remove':
                (self.base / 'Pacote/concurrent.txt').write_bytes(b'keep')
            return result
        with patch.object(self.engine, 'call', side_effect=changed):
            self.assertFailure('RELEASE_UNCERTAIN', self.apply, identity, plan)
        self.assertEqual([], self.row(identity)['release']['removed'])
        self.assertTrue((self.base / 'Pacote/filme.mkv').exists())

    def test_symlinks_and_unsafe_library_selection_never_authorize_release(self):
        identity = self.prepare()
        plan = self.preview(identity)
        unsafe = self.base / 'Pacote/unsafe'
        unsafe.symlink_to(self.home)
        self.assertIn('UNSAFE_PATH', self.preview(identity)['blockers'])
        self.assertFailure('BLOCKED', self.apply, identity, plan)
        self.assertFailure('UNSAFE_PATH', self.run_cli, 'space', identity, '--remove-library', '../escape')
        self.assertEqual([], self.mutations())

    def test_same_device_bind_mount_blocks_without_traversing_payload(self):
        identity = self.prepare(False, False)
        real_read = tool.Path.read_text
        def read(path, *a, **kw):
            if str(path) == '/proc/self/mountinfo':
                return '1 2 0:1 / ' + str(self.base / 'Pacote') + ' rw - btrfs none rw\n'
            return real_read(path, *a, **kw)
        with patch.object(tool.Path, 'read_text', read):
            self.assertIn('UNSAFE_PATH', self.preview(identity)['blockers'])
        self.assertEqual([], self.mutations()); self.assertTrue(self.base.exists())

    def test_relink_acl_failure_preserves_source_and_can_be_retried_without_duplicates(self):
        identity = self.prepare()
        plan = self.preview(identity, True); self.apply(identity, plan, True)
        self.run_cli('restore', identity); self.run_cli('recover', identity)
        def fail(*args):
            raise tool.media.subprocess.CalledProcessError(1, 'setfacl')
        with patch.object(tool.media, 'ensure_access', side_effect=fail):
            self.assertFailure('MEDIA', self.run_cli, 'relink', identity, '--library', self.rel)
        self.assertFalse(self.link.exists())
        self.assertTrue((tool.restored_path(self.row(identity)) / 'Pacote/filme.mkv').exists())
        with patch.object(tool.media, 'ensure_access'):
            self.assertEqual('added', self.run_cli('relink', identity, '--library', self.rel)['library'][0]['outcome'])

    def test_start_requires_observed_verification_and_correct_location_even_after_valid_pieces(self):
        identity = self.prepare()
        original = self.engine.call
        def redirected(method, params):
            result = original(method, params)
            if method == 'torrent_start':
                self.engine.row['download_dir'] = str(self.home / 'wrong')
            return result
        with patch.object(self.engine, 'call', side_effect=redirected):
            self.assertFailure('STATE_CHANGED', self.run_cli, 'recover', identity, '--resume')
        self.assertEqual(0, self.engine.row['status'])

    def test_corrupt_restore_blocks_before_reimport_and_insufficient_space_prevents_copy(self):
        identity = self.prepare()
        self.apply(identity, self.preview(identity, True), True)
        with patch.object(tool.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 0})()):
            self.assertFailure('LOCAL_SPACE', self.run_cli, 'restore', identity)
        self.cloud.mode = 'corrupt'
        self.assertFailure('INTEGRITY', self.run_cli, 'restore', identity)
        self.assertFailure('INTEGRITY', self.run_cli, 'recover', identity, '--resume')
        self.assertNotIn('torrent_add', self.mutations()); self.assertNotIn('torrent_start', self.mutations())


if __name__ == '__main__':
    unittest.main()
