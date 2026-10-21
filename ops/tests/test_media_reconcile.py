"""Disposable reanchoring plan/transformation; no installed helper or live RPC."""
import copy
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_media_cleanup as fixtures


class ReconcileTests(unittest.TestCase):
    def setUp(self):
        self.f = fixtures.CleanupTests()
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        self.m = fixtures.cleanup.core
        cli = self.f.home / '.local/bin/myct-torrent'
        cli.parent.mkdir(parents=True)
        cli.write_bytes(Path(self.m.__file__).with_name('myct-torrent').read_bytes())
        self.fs = {'kind': 'btrfs', 'uuid': 'a' * 32, 'subvolume': 257}
        p = patch.object(self.m, 'filesystem_identity', return_value=self.fs)
        p.start(); self.addCleanup(p.stop)
        # Exact incident numbers; physical files/inodes remain disposable and real.
        for method in ('stat', 'lstat'):
            original = getattr(Path, method)
            def current(path, *args, _original=original, **kwargs):
                value = _original(path, *args, **kwargs)
                attrs = {n: getattr(value, n) for n in dir(value) if n.startswith('st_')}
                attrs['st_dev'] = 51
                return SimpleNamespace(**attrs)
            p = patch.object(Path, method, current)
            p.start(); self.addCleanup(p.stop)

    def add(self, n=1, old=True):
        rel, src, dest = self.f.add(n)
        if old:
            self.f.state['links'][rel]['dev'] = 50
        self.f.rows[0]['files'] = [{'name': p.name, 'length': p.stat().st_size}
                                 for p in self.f.source_root.iterdir() if p.is_file()]
        return rel, src, dest

    def plan(self):
        return self.m.reconciliation_plan(self.f.rows, self.f.state)

    def test_thirteen_legacy_50_to_51_proposed_recovery_without_writes_or_consent(self):
        for n in range(1, 14): self.add(n)
        self.m.save_state(self.f.state)
        before = self.m.state_path().read_bytes()
        plan = self.plan()
        self.assertEqual(13, plan['eligible'])
        self.assertEqual({'LEGACY_REANCHOR'}, {e['reason'] for e in plan['entries']})
        self.assertTrue(plan['legacy_requires_explicit_reanchor'])
        with self.assertRaises(self.m.MediaError):
            self.m.reconciled_state(self.f.state, plan, self.plan(), plan['fingerprint'])
        proposed = self.m.reconciled_state(self.f.state, plan, self.plan(), plan['fingerprint'], accept_legacy=True)
        self.assertEqual(0, sum(f['eligible'] for f in fixtures.cleanup.snapshot(self.f.rows, self.f.state)['files']))
        self.assertEqual(13, sum(f['eligible'] for f in fixtures.cleanup.snapshot(self.f.rows, proposed)['files']))
        self.assertTrue(all(e['dev'] == 51 and e['filesystem'] == self.fs for e in proposed['links'].values()))
        self.assertTrue(all(e['dev'] == 50 for e in self.f.state['links'].values()))
        self.assertEqual(before, self.m.state_path().read_bytes())
        self.assertEqual([], proposed['ignored'])

    def test_correct_legacy_and_new_records_need_no_migration(self):
        rel, src, dest = self.add(old=False)
        self.assertEqual('CURRENT', self.plan()['entries'][0]['reason'])
        self.f.state['links'][rel] = self.m.link_record(fixtures.HASH + ':' + src.name, dest)
        self.assertEqual(0, self.plan()['eligible'])
        self.assertEqual('CURRENT', self.plan()['entries'][0]['reason'])

    def test_wrong_origin_with_recorded_inode_collision_remains_protected(self):
        rel, src, dest = self.add()
        _, other, _ = self.add(2)
        self.f.state['links'][rel]['id'] = fixtures.HASH + ':' + other.name
        entry = next(e for e in self.plan()['entries'] if e['path'] == rel)
        self.assertFalse(entry['eligible'])
        self.assertEqual('SOURCE_INODE_MISMATCH', entry['reason'])

    def test_old_original_alias_and_independent_current_rpc_source_exception(self):
        rel, src, dest = self.add()
        old = self.f.home / 'archived-original.mkv'
        src.rename(old)
        src.write_bytes(old.read_bytes())
        self.assertEqual(2, dest.stat().st_nlink)
        self.assertEqual('SOURCE_INODE_MISMATCH', self.plan()['entries'][0]['reason'])

    def test_missing_source_library_and_replaced_source_are_not_recovered(self):
        rel, src, dest = self.add()
        src.unlink()
        self.assertEqual(0, self.plan()['eligible'])
        src.write_bytes(b'other')
        self.assertEqual(0, self.plan()['eligible'])
        dest.unlink()
        self.assertEqual('ABSENT', self.plan()['entries'][0]['reason'])

    def test_unknown_hardlink_and_ignored_are_protected(self):
        rel, src, dest = self.add()
        alias = self.f.home / 'unknown'; os.link(src, alias)
        self.assertEqual('UNKNOWN_LINKS', self.plan()['entries'][0]['reason'])
        alias.unlink()
        self.f.state['ignored'].append(self.f.state['links'][rel]['id'])
        self.assertEqual('IGNORED', self.plan()['entries'][0]['reason'])

    def test_expected_torrent_size_and_completion_are_required(self):
        self.add()
        self.f.rows[0]['files'][0]['length'] += 1
        self.assertEqual('TORRENT_METADATA_MISMATCH', self.plan()['entries'][0]['reason'])
        self.f.rows[0]['left_until_done'] = 1
        self.assertEqual(0, self.plan()['eligible'])
        self.f.rows[0]['left_until_done'] = 0
        self.f.rows[0]['error'] = 3
        self.assertEqual(0, self.plan()['eligible'])

    def test_filesystem_replacement_and_saved_metadata_changes_block(self):
        rel, src, dest = self.add()
        info = self.m.link_record(self.f.state['links'][rel]['id'], dest)
        info['dev'] = 50
        self.f.state['links'][rel] = info
        self.assertEqual('DEVICE_REANCHOR', self.plan()['entries'][0]['reason'])
        info['filesystem'] = dict(self.fs, uuid='b' * 32)
        self.assertEqual('FILESYSTEM_CHANGED', self.plan()['entries'][0]['reason'])
        info['filesystem'] = self.fs
        info['metadata']['mtime_ns'] += 1
        self.assertEqual('RECORDED_METADATA_MISMATCH', self.plan()['entries'][0]['reason'])

    def test_enriched_identity_does_not_silently_accept_future_device_change(self):
        rel, _, dest = self.add()
        info = self.m.link_record(self.f.state['links'][rel]['id'], dest)
        info['dev'] = 50  # Current stat is 51; UUID/subvolume/other metadata match.
        self.f.state['links'][rel] = copy.deepcopy(info)
        self.assertFalse(self.m.record_matches(info, info['id'], dest))
        self.assertFalse(fixtures.cleanup.snapshot(self.f.rows, self.f.state)['files'][0]['eligible'])
        plan = self.plan()
        self.assertEqual('DEVICE_REANCHOR', plan['entries'][0]['reason'])
        self.assertFalse(plan['legacy_requires_explicit_reanchor'])
        self.assertEqual(info, self.f.state['links'][rel])
        with self.assertRaises(self.m.MediaError):
            self.m.reconciled_state(self.f.state, plan, plan, 'not-confirmed')
        result = self.m.reconciled_state(self.f.state, plan, plan, plan['fingerprint'])
        self.assertEqual(51, result['links'][rel]['dev'])

    def test_unknown_filesystem_and_symlink_are_never_reanchored(self):
        rel, src, dest = self.add()
        with patch.object(self.m, 'filesystem_identity', return_value=None):
            self.assertEqual('FILESYSTEM_UNPROVEN', self.plan()['entries'][0]['reason'])
        dest.unlink(); dest.symlink_to(src)
        self.assertEqual(0, self.plan()['eligible'])

    def test_fresh_plan_changes_token_state_and_file_changes_reject(self):
        rel, src, dest = self.add()
        plan = self.plan()
        with self.assertRaises(self.m.MediaError):
            self.m.reconciled_state(self.f.state, plan, self.plan(), 'incorrect', True)
        src.write_bytes(b'changed while reviewing')
        with self.assertRaises(self.m.MediaError):
            self.m.reconciled_state(self.f.state, plan, self.plan(), plan['fingerprint'], True)
        self.f.state['ignored'].append('unrelated')
        with self.assertRaises(self.m.MediaError):
            self.m.reconciled_state(self.f.state, plan, plan, plan['fingerprint'], True)

    def test_duplicate_rpc_sources_are_ambiguous(self):
        self.add(); self.f.rows.append(copy.deepcopy(self.f.rows[0]))
        self.assertEqual(0, self.plan()['eligible'])

    def test_recorded_inode_mismatch_and_invalid_record_are_protected(self):
        rel, _, _ = self.add()
        self.f.state['links'][rel]['ino'] += 1
        self.assertEqual('RECORDED_INODE_MISMATCH', self.plan()['entries'][0]['reason'])
        self.f.state['links'][rel] = None
        self.assertEqual('INVALID_RECORD', self.plan()['entries'][0]['reason'])

    def test_cli_plan_does_not_open_lock_or_write_and_apply_needs_arguments(self):
        self.add(); self.m.save_state(self.f.state)
        before = self.m.state_path().read_bytes()
        plan = self.m.run(['reconcile-plan'], self.f.rows)
        self.assertEqual(1, plan['eligible']); self.assertFalse(plan['application_supported'])
        self.assertFalse(self.m.state_path().with_suffix('.lock').exists())
        with self.assertRaises(self.m.MediaError): self.m.run(['reconcile-apply'], self.f.rows)
        self.assertEqual(before, self.m.state_path().read_bytes())

    def test_change_while_collecting_identity_blocks_candidate(self):
        rel, src, dest = self.add()
        changed = [False]
        def filesystem(path):
            if not changed[0]:
                changed[0] = True
                src.write_bytes(b'changed during plan')
            return self.fs
        with patch.object(self.m, 'filesystem_identity', side_effect=filesystem):
            self.assertEqual('CHANGED_DURING_PLAN', self.plan()['entries'][0]['reason'])

    def test_metadata_record_cannot_grant_cleanup_after_content_change(self):
        rel, src, dest = self.add(old=False)
        self.f.state['links'][rel] = self.m.link_record(self.f.state['links'][rel]['id'], dest)
        src.write_bytes(b'changed on same inode')
        self.assertFalse(fixtures.cleanup.snapshot(self.f.rows, self.f.state)['files'][0]['eligible'])

    def test_scan_does_not_claim_stale_record_as_managed_or_repair_it(self):
        self.add()
        before = json.dumps(self.f.state, sort_keys=True)
        result = self.m.inspect(self.f.rows, self.f.state)
        self.assertEqual([], result['existing'])
        self.assertIn('reconciliação', result['pending'][0]['reason'])
        self.assertEqual(before, json.dumps(self.f.state, sort_keys=True))

    def test_btrfs_identity_uses_uuid_and_subvolume_not_device_number(self):
        _, src, _ = self.add()
        calls = []
        def ioctl(fd, code, buf):
            calls.append((code, len(buf)))
            if code == 0x8400941f: buf[16:32] = b'a' * 16
            else: buf[:8] = (257).to_bytes(8, os.sys.byteorder)
        # Remove the fixture identity patch temporarily to exercise the real reader.
        from test_media import media
        with patch.object(media.fcntl, 'ioctl', side_effect=ioctl):
            identity = media.filesystem_identity(src)
        self.assertEqual({'kind': 'btrfs', 'uuid': (b'a' * 16).hex(), 'subvolume': 257}, identity)
        self.assertEqual([(0x8400941f, 1024), (0xd0009412, 4096)], calls)


if __name__ == '__main__': unittest.main()
