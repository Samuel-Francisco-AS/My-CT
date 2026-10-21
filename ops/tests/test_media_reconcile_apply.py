"""Writer tests: disposable catalogs/media, fake RPC/Btrfs, no installed helpers."""
import copy
import importlib.util
import json
import multiprocessing
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import test_media_cleanup as fixtures

spec = importlib.util.spec_from_file_location('media_writer', Path(__file__).parents[1] / 'myct_media_reconcile.py')
writer = importlib.util.module_from_spec(spec); spec.loader.exec_module(writer)


class ApplyTests(unittest.TestCase):
    def setUp(self):
        self.f = fixtures.CleanupTests(); self.f.setUp(); self.addCleanup(self.f.doCleanups)
        self.core = fixtures.cleanup.core
        self.fs = {'kind': 'btrfs', 'uuid': 'a' * 32, 'subvolume': 257}
        p = patch.object(self.core, 'filesystem_identity', return_value=self.fs)
        p.start(); self.addCleanup(p.stop)
        cli = self.f.home / '.local/bin/myct-torrent'; cli.parent.mkdir(parents=True)
        cli.write_bytes(Path(self.core.__file__).with_name('myct-torrent').read_bytes())
        original = self.core.read_torrents
        def read(include_files=False, rows=None):
            return original(include_files, self.f.rows if rows is None else rows)
        p = patch.object(self.core, 'read_torrents', side_effect=read)
        p.start(); self.addCleanup(p.stop)
        self.elapsed = 0
        p = patch.object(writer.time, 'monotonic', side_effect=lambda: self.elapsed)
        p.start(); self.addCleanup(p.stop)
        self.now = 1000000
        p = patch.object(writer.time, 'time', side_effect=lambda: self.now)
        p.start(); self.addCleanup(p.stop)
        self.proof = self.f.home / 'provenance.json'
        self.proof.write_bytes(writer.encoded({'schema': 1, 'owner_verified_origin': True,
            'historical_identity_missing': True, 'filesystems': [self.fs],
            'evidence_reference': 'fixture: owner checked original volume against independent inventory'}))
        self.proof.chmod(0o600)
        for path in writer.catalogs():
            path.parent.mkdir(parents=True, mode=0o700)
            path.write_bytes(writer.encoded({'version': 1, 'entries': {}})); path.chmod(0o600)
        self.add(1); self.save()

    def add(self, n, old=True):
        rel, src, dest = self.f.add(n)
        if old: self.f.state['links'][rel]['dev'] -= 1
        return rel, src, dest

    def save(self):
        self.f.rows[0]['files'] = [{'name': p.name, 'length': p.stat().st_size}
                                 for p in self.f.source_root.iterdir() if p.is_file()]
        self.core.save_state(self.f.state)
        self.before = self.core.state_path().read_bytes()

    def preview(self):
        return writer.preview(self.core, self.proof)

    def apply(self, plan):
        return writer.apply(self.core, plan, self.proof, plan['id'], plan['id'])

    def test_264_updates_preserve_fourteen_exception_absent_ignored_and_media(self):
        for n in range(2, 265): self.add(n)
        for n in range(265, 279): self.add(n, old=False)
        exceptional, src, dest = self.add(279)
        src.rename(self.f.home / 'archived-original.mkv'); src.write_bytes(b'different current origin')
        for n in range(28):
            self.f.state['links'][f'Series/Absent/Season 01/E{n:02d}.mkv'] = {
                'id': fixtures.HASH + ':absent' + str(n), 'dev': 50, 'ino': n + 1}
        self.f.state['ignored'] = ['unrelated:preserve', 'another:preserve']
        self.save(); state = copy.deepcopy(self.f.state)
        files = {p: (p.stat().st_ino, p.stat().st_nlink, p.read_bytes())
                 for p in (self.f.home / 'Media').rglob('*.mkv')}
        archives = [p.read_bytes() for p in writer.catalogs()]
        plan = self.preview()
        self.assertEqual({'LEGACY_REANCHOR': 264, 'CURRENT': 14, 'SOURCE_INODE_MISMATCH': 1, 'ABSENT': 28}, plan['summary'])
        reply = self.apply(plan); self.assertEqual(264, reply['updated']); self.assertEqual('succeeded', reply['state'])
        result = self.core.load_state()
        for rel, info in state['links'].items():
            if rel in plan['updates']:
                self.assertEqual(info['id'], result['links'][rel]['id'])
                self.assertEqual(self.fs, result['links'][rel]['filesystem'])
            else: self.assertEqual(info, result['links'][rel])
        self.assertEqual(state['ignored'], result['ignored'])
        self.assertEqual(archives, [p.read_bytes() for p in writer.catalogs()])
        self.assertEqual(files, {p: (p.stat().st_ino, p.stat().st_nlink, p.read_bytes()) for p in files})
        backup = writer.root() / plan['id'] / 'state.before.json'
        self.assertEqual(self.before, backup.read_bytes()); self.assertEqual(0o600, backup.stat().st_mode & 0o777)
        self.assertEqual(0o700, backup.parent.stat().st_mode & 0o777)
        self.assertNotIn(str(self.f.home), json.dumps(reply))

    def test_preview_is_read_only_and_provenance_is_specific(self):
        before = sorted(str(p) for p in self.f.home.rglob('*'))
        p = self.preview()
        self.assertEqual(before, sorted(str(p) for p in self.f.home.rglob('*')))
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        doc = json.loads(self.proof.read_text()); doc['filesystems'][0]['uuid'] = 'b' * 32
        self.proof.write_bytes(writer.encoded(doc))
        with self.assertRaises(writer.Error): self.apply(p)

    def test_matching_ignored_record_is_preserved_by_writer(self):
        rel = next(iter(self.f.state['links']))
        original = copy.deepcopy(self.f.state['links'][rel])
        self.f.state['ignored'] = [original['id']]
        self.add(2); self.save()
        p = self.preview(); self.assertEqual(1, p['summary']['IGNORED'])
        self.assertEqual(1, self.apply(p)['updated'])
        result = self.core.load_state()
        self.assertEqual(original, result['links'][rel])
        self.assertEqual([original['id']], result['ignored'])

    def test_exact_legacy_confirmation_and_expiration(self):
        p = self.preview()
        for confirmation, legacy in [('yes', p['id']), (p['id'], 'yes'), (p['id'], None)]:
            with self.assertRaises(writer.Error): writer.apply(self.core, p, self.proof, confirmation, legacy)
        self.now += writer.TTL
        with self.assertRaisesRegex(writer.Error, 'EXPIRED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_plan_rpc_and_index_changes_refuse(self):
        p = self.preview()
        changed = copy.deepcopy(p); changed['updates'] = []
        with self.assertRaises(writer.Error): self.apply(changed)
        self.f.rows[0]['status'] = 0
        with self.assertRaisesRegex(writer.Error, 'PLAN_CHANGED'): self.apply(p)
        self.f.rows[0]['status'] = 6
        self.f.state['ignored'].append('new'); self.core.save_state(self.f.state)
        expected = self.core.state_path().read_bytes()
        with self.assertRaisesRegex(writer.Error, 'PLAN_CHANGED'): self.apply(p)
        self.assertEqual(expected, self.core.state_path().read_bytes())

    def test_active_pending_and_uncertain_jobs_refuse(self):
        p = self.preview(); jobs = self.f.home / '.local/share/myct/cloud-jobs'
        jobs.mkdir(mode=0o700)
        task = jobs / ('a' * 32 + '.json')
        for state in ('starting', 'running', 'pending', 'cancelling', 'uncertain'):
            task.write_bytes(writer.encoded({'id': 'a' * 32, 'state': state})); task.chmod(0o600)
            with self.assertRaisesRegex(writer.Error, 'CONFLICTING_JOB'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_uncertain_catalog_and_journal_changes_refuse(self):
        p = self.preview(); archive = writer.catalogs()[1]
        archive.write_bytes(writer.encoded({'entries': {'x': {'state': 'verified', 'release': {'state': 'uncertain'}}}}))
        with self.assertRaisesRegex(writer.Error, 'CATALOG_STATE_UNCERTAIN'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_backup_failure_requires_recovery_without_any_catalog_change(self):
        p = self.preview(); original = writer.write_exclusive
        def fail(fd, name, raw):
            if name == 'state.before.json': raise OSError('synthetic private path/token')
            return original(fd, name, raw)
        with patch.object(writer, 'write_exclusive', side_effect=fail):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        recovered = writer.recover(self.core, p['id'])
        self.assertEqual('not_applied', recovered['state']); self.assertEqual(0, recovered['updated'])
        self.assertEqual(1, recovered['planned_updates'])

    def test_data_change_after_backup_aborts_before_catalog_replace(self):
        p = self.preview(); original = writer.write_exclusive
        src = next(self.f.source_root.iterdir())
        def change(fd, name, raw):
            result = original(fd, name, raw)
            if name == 'state.before.json': src.write_bytes(b'changed during apply')
            return result
        with patch.object(writer, 'write_exclusive', side_effect=change):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        self.assertEqual('not_applied', writer.recover(self.core, p['id'])['state'])

    def test_execution_deadline_after_backup_refuses(self):
        p = self.preview(); original = writer.write_exclusive
        def expire(fd, name, raw):
            result = original(fd, name, raw)
            if name == 'state.before.json': self.elapsed += writer.TTL
            return result
        with patch.object(writer, 'write_exclusive', side_effect=expire):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_long_hash_passes_exceed_600_but_exact_consent_is_accepted_fresh(self):
        original = writer.content
        def slow(core, plan):
            result = original(core, plan)
            self.elapsed += 750; self.now += 750
            return result
        with patch.object(writer, 'content', side_effect=slow):
            p = self.preview()
            self.assertEqual(750, p['workload']['preview_seconds'])
            self.assertEqual({'collect': 0, 'hash': 750, 'recheck': 0}, p['preview_phases_seconds'])
            self.assertEqual(p['workload']['bytes_per_pass'] / 750, p['workload']['observed_effective_bytes_per_second'])
            self.assertEqual(2370, p['workload']['execution_limit_seconds'])
            self.assertTrue(p['workload']['temporal_viable'])
            self.now += 599; self.elapsed += 599
            self.assertEqual('succeeded', self.apply(p)['state'])
        self.assertGreater(self.now, p['expires'])
        self.assertEqual(self.before, (writer.root() / p['id'] / 'state.before.json').read_bytes())

    def test_estimate_volume_distinct_passes_unknown_rate_and_read_only_cli(self):
        self.add(2); self.save()
        paths = sorted(str(p) for p in self.f.home.rglob('*'))
        with patch.object(writer, 'content', side_effect=AssertionError('estimate must not hash')):
            result = writer.estimate(self.core, self.proof)
        cli = self.core.run(['reconcile-estimate', '--provenance', str(self.proof)])
        self.assertEqual(result, cli)
        self.assertEqual(paths, sorted(str(p) for p in self.f.home.rglob('*')))
        w = result['workload']
        size = sum(p.stat().st_size for p in self.f.source_root.iterdir())
        self.assertEqual((2, size, 2, 2 * size), (w['distinct_files'], w['bytes_per_pass'], w['full_passes'], w['total_read_bytes']))
        self.assertIsNone(w['observed_effective_bytes_per_second']); self.assertIsNone(w['temporal_viable'])
        inner = self.preview()['snapshot']['plan']
        inner['entries'].append(copy.deepcopy(inner['entries'][0])); inner['eligible'] += 1
        self.assertEqual(size, writer.workload(inner)['bytes_per_pass'])
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_invalid_budget_or_phase_diagnostic_is_not_accepted(self):
        for field, value in [('execution_limit_seconds', writer.MAX_EXECUTION + 1), ('preview_seconds', -1)]:
            p = self.preview(); p['workload'][field] = value
            with self.assertRaisesRegex(writer.Error, 'INVALID_TIMING'): self.apply(p)
        p = self.preview(); p['preview_phases_seconds']['hash'] = 1
        with self.assertRaisesRegex(writer.Error, 'INVALID_TIMING'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_exact_consent_integrity_rechecked_before_publication(self):
        p = self.preview(); original = writer.write_exclusive
        def tamper(fd, name, raw):
            result = original(fd, name, raw)
            if name == 'state.before.json': p['nonce'] = 'f' * 32
            return result
        with patch.object(writer, 'write_exclusive', side_effect=tamper):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        self.assertEqual('not_applied', writer.recover(self.core, p['id'])['state'])

    def test_temporal_preflight_cap_refuses_before_hash_or_operation_creation(self):
        original = writer.content
        def slow(core, plan):
            result = original(core, plan); self.elapsed += writer.MAX_EXECUTION
            return result
        with patch.object(writer, 'content', side_effect=slow): p = self.preview()
        self.assertFalse(p['workload']['temporal_viable'])
        with patch.object(writer, 'content', side_effect=AssertionError('must preflight first')):
            with self.assertRaisesRegex(writer.Error, 'TEMPORAL_PREFLIGHT'): self.apply(p)
        self.assertFalse(writer.root().exists()); self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_unexpected_hash_slowdown_aborts_before_intent(self):
        p = self.preview(); original = writer.content
        def slow(core, plan):
            result = original(core, plan); self.elapsed += p['workload']['execution_limit_seconds']
            return result
        with patch.object(writer, 'content', side_effect=slow):
            with self.assertRaisesRegex(writer.Error, 'EXECUTION_EXPIRED'): self.apply(p)
        self.assertFalse(writer.root().exists()); self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_expiry_is_rechecked_under_locks_and_old_plans_refused(self):
        from contextlib import contextmanager
        p = self.preview(); original = writer.locks
        @contextmanager
        def delayed(core, hashes):
            with original(core, hashes):
                self.now += writer.TTL
                yield
        with patch.object(writer, 'locks', side_effect=delayed):
            with self.assertRaisesRegex(writer.Error, 'EXPIRED'): self.apply(p)
        self.assertFalse(writer.root().exists())
        p['schema'] = 1
        with self.assertRaisesRegex(writer.Error, 'INVALID_PLAN'): self.apply(p)

    def test_execution_limit_after_backup_is_recoverable_and_wall_clock_jump_does_not_extend(self):
        p = self.preview(); original = writer.write_exclusive
        def expire(fd, name, raw):
            result = original(fd, name, raw)
            if name == 'state.before.json':
                self.now -= 10000; self.elapsed += p['workload']['execution_limit_seconds']
            return result
        with patch.object(writer, 'write_exclusive', side_effect=expire):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        self.assertEqual('not_applied', writer.recover(self.core, p['id'])['state'])

    def fail_first_intent(self, p, stage='atomic'):
        original_atomic, original_replace, original_sync = writer.atomic, writer.os.replace, writer.os.fsync
        published = False
        def fail_atomic(fd, name, raw):
            if name == 'transaction.json': raise OSError('synthetic private token')
            return original_atomic(fd, name, raw)
        def fail_replace(src, dst, **kwargs):
            nonlocal published
            if dst == 'transaction.json' and stage == 'replace': raise OSError('synthetic initial rename failure')
            result = original_replace(src, dst, **kwargs)
            if dst == 'transaction.json': published = True
            return result
        def fail_sync(fd):
            path = os.readlink('/proc/self/fd/' + str(fd))
            if ((stage == 'fsync' or stage == 'published_fsync' and published) and path == str(writer.root() / p['id']) or
                    stage == 'file_fsync' and path.startswith(str(writer.root() / p['id'] / '.reconcile-')) or
                    stage == 'operations_fsync' and path == str(writer.root()) and (writer.root() / p['id']).exists()):
                raise OSError('synthetic initial fsync failure')
            return original_sync(fd)
        with patch.object(writer, 'atomic', side_effect=fail_atomic if stage == 'atomic' else original_atomic), \
             patch.object(writer.os, 'replace', side_effect=fail_replace if stage in ('replace', 'published_fsync') else original_replace), \
             patch.object(writer.os, 'fsync', side_effect=fail_sync if stage in ('fsync', 'file_fsync', 'published_fsync', 'operations_fsync') else original_sync):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED: ' + p['id']): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_first_intent_failure_has_operation_id_and_explicit_recovery_without_deletion(self):
        p = self.preview(); self.fail_first_intent(p)
        directory = writer.root() / p['id']
        self.assertTrue(directory.is_dir()); self.assertEqual([], list(directory.iterdir()))
        with self.assertRaisesRegex(writer.Error, 'MISSING_INTENT_REQUIRES_PLAN'): writer.recover(self.core, p['id'])
        with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(self.preview())
        self.now += writer.TTL  # Recovery cannot apply; an expired exact plan suffices.
        file = self.f.home / 'approved-plan.json'; file.write_bytes(writer.encoded(p)); file.chmod(0o600)
        r = self.core.run(['reconcile-recover', '--operation', p['id'], '--plan', str(file)])
        self.assertEqual(('not_applied', 0), (r['state'], r['updated']))
        self.assertTrue(r['recovered_missing_intent'])
        j = json.loads((directory / 'transaction.json').read_bytes())
        self.assertTrue(j['recovered_missing_intent']); self.assertEqual([], j['preserved_evidence'])
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        self.assertEqual('succeeded', self.apply(self.preview())['state'])

    def test_first_intent_rename_and_fsync_faults_preserve_and_classify(self):
        for stage in ('replace', 'fsync', 'file_fsync', 'published_fsync', 'operations_fsync'):
            with self.subTest(stage=stage):
                p = self.preview(); self.fail_first_intent(p, stage)
                self.assertEqual('not_applied', writer.recover(self.core, p['id'], p)['state'])
                self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_missing_intent_preserves_orphan_evidence_and_refuses_wrong_plan(self):
        p = self.preview(); self.fail_first_intent(p)
        orphan = writer.root() / p['id'] / ('.reconcile-' + 'c' * 32)
        orphan.write_bytes(b'partial initial intent'); orphan.chmod(0o600)
        with self.assertRaisesRegex(writer.Error, 'CONFIRMATION'): writer.recover(self.core, p['id'], self.preview())
        writer.recover(self.core, p['id'], p)
        self.assertEqual(b'partial initial intent', orphan.read_bytes())
        journal = json.loads(orphan.with_name('transaction.json').read_bytes())
        self.assertEqual([[orphan.name, writer.digest(orphan.read_bytes())]], journal['preserved_evidence'])
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_missing_intent_unknown_evidence_or_changed_catalog_cannot_be_bypassed(self):
        p = self.preview(); self.fail_first_intent(p)
        marker = writer.root() / p['id'] / 'state.before.json'
        marker.write_bytes(self.before); marker.chmod(0o600)
        with self.assertRaisesRegex(writer.Error, 'UNCLASSIFIED_EVIDENCE'): writer.recover(self.core, p['id'], p)
        self.assertEqual(self.before, marker.read_bytes())
        marker.rename(marker.with_name('.reconcile-' + 'd' * 32))  # Fixture only.
        self.f.state['ignored'].append('newer'); self.core.save_state(self.f.state)
        newer = self.core.state_path().read_bytes()
        with self.assertRaisesRegex(writer.Error, 'UNCLASSIFIED_CATALOG'): writer.recover(self.core, p['id'], p)
        self.assertEqual(newer, self.core.state_path().read_bytes())
        self.assertFalse(marker.with_name('transaction.json').exists())

    def test_missing_intent_recovery_write_failure_keeps_evidence_and_catalog(self):
        p = self.preview(); self.fail_first_intent(p)
        for name, target in [('atomic', writer), ('fsync', writer.os)]:
            with patch.object(target, name, side_effect=OSError('fixture')):
                with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): writer.recover(self.core, p['id'], p)
        self.assertEqual([], list((writer.root() / p['id']).iterdir()))
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        self.assertEqual('not_applied', writer.recover(self.core, p['id'], p)['state'])

    def test_interruption_before_and_after_atomic_catalog_write(self):
        for after in (False, True):
            with self.subTest(after=after):
                p = self.preview(); original = writer.os.replace
                def interrupt(src, dst, **kwargs):
                    if dst == 'state.json':
                        if after: original(src, dst, **kwargs)
                        raise KeyboardInterrupt()
                    return original(src, dst, **kwargs)
                with patch.object(writer.os, 'replace', side_effect=interrupt):
                    with self.assertRaises(KeyboardInterrupt): self.apply(p)
                result = writer.recover(self.core, p['id'])
                self.assertEqual('succeeded' if after else 'not_applied', result['state'])
                if not after: self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_recovery_never_overwrites_newer_catalog(self):
        p = self.preview(); original = writer.os.replace
        def stop(src, dst, **kwargs):
            if dst == 'state.json': raise KeyboardInterrupt()
            return original(src, dst, **kwargs)
        with patch.object(writer.os, 'replace', side_effect=stop):
            with self.assertRaises(KeyboardInterrupt): self.apply(p)
        self.f.state['ignored'].append('later-change'); self.core.save_state(self.f.state)
        newer = self.core.state_path().read_bytes()
        recovered = writer.recover(self.core, p['id'])
        self.assertEqual('superseded', recovered['state']); self.assertIsNone(recovered['updated'])
        self.assertEqual(newer, self.core.state_path().read_bytes())

    def test_repeated_apply_is_read_only_and_does_not_overwrite_backup(self):
        p = self.preview(); first = self.apply(p)
        after = self.core.state_path().stat().st_mtime_ns
        backup = writer.root() / p['id'] / 'state.before.json'
        stamp = backup.stat().st_mtime_ns
        again = self.apply(p)
        self.assertTrue(again['already_applied']); self.assertEqual(first['operation'], again['operation'])
        self.assertEqual(after, self.core.state_path().stat().st_mtime_ns)
        self.assertEqual(stamp, backup.stat().st_mtime_ns)
        self.assertEqual(self.before, backup.read_bytes())

    def test_lock_order_and_concurrent_application(self):
        p = self.preview()
        with writer.locks(self.core, [fixtures.HASH]):
            with self.assertRaisesRegex(writer.Error, 'BUSY'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        ctx = multiprocessing.get_context('fork'); start = ctx.Event(); queue = ctx.Queue()
        def child():
            start.wait(3)
            try: queue.put(self.apply(p)['state'])
            except writer.Error as error: queue.put(error.code)
        children = [ctx.Process(target=child) for _ in range(2)]
        for proc in children: proc.start()
        start.set()
        results = [queue.get(timeout=10) for _ in children]
        for proc in children: proc.join(5); self.assertFalse(proc.is_alive())
        self.assertIn('succeeded', results)
        self.assertTrue(set(results) <= {'succeeded', 'BUSY'})
        self.assertEqual(1, len(list(writer.root().iterdir())))

    def test_symlink_hardlinked_index_and_unsafe_lock_refuse(self):
        p = self.preview(); path = self.core.state_path()
        alias = self.f.home / 'alias'; os.link(path, alias)
        with self.assertRaises(writer.Error): self.apply(p)
        alias.unlink()
        lock = path.with_suffix('.lock'); victim = self.f.home / 'victim'; victim.write_bytes(b'unchanged')
        lock.unlink()  # Fixture lock created by the refused attempt above; no holder.
        lock.symlink_to(victim)
        with self.assertRaises(OSError): self.apply(p)
        self.assertEqual(b'unchanged', victim.read_bytes())
        self.assertEqual(self.before, path.read_bytes())

    def test_post_rename_fsync_failure_recovers_without_rollback(self):
        p = self.preview(); original = writer.os.fsync
        def fail(fd):
            if (os.readlink('/proc/self/fd/' + str(fd)) == str(self.core.state_path().parent) and
                    self.core.state_path().read_bytes() != self.before):
                raise OSError('synthetic fsync failure')
            return original(fd)
        with patch.object(writer.os, 'fsync', side_effect=fail):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertNotEqual(self.before, self.core.state_path().read_bytes())
        self.assertEqual('succeeded', writer.recover(self.core, p['id'])['state'])

    def test_backup_corruption_refuses_recovery(self):
        p = self.preview(); self.apply(p)
        (writer.root() / p['id'] / 'state.before.json').write_bytes(b'corrupt')
        after = self.core.state_path().read_bytes()
        with self.assertRaisesRegex(writer.Error, 'BACKUP_INVALID'): writer.recover(self.core, p['id'])
        self.assertEqual(after, self.core.state_path().read_bytes())

    def test_temp_write_and_replace_failures_keep_original_and_backup(self):
        for stage in ('write', 'replace'):
            with self.subTest(stage=stage):
                p = self.preview()
                write = writer.write_exclusive; replace = writer.os.replace
                def fail_write(fd, name, raw):
                    if stage == 'write' and raw != self.before and raw.startswith(b'{"ignored"'):
                        raise OSError('synthetic temp write failure')
                    return write(fd, name, raw)
                def fail_replace(src, dst, **kwargs):
                    if stage == 'replace' and dst == 'state.json': raise OSError('synthetic rename failure')
                    return replace(src, dst, **kwargs)
                with patch.object(writer, 'write_exclusive', side_effect=fail_write), patch.object(writer.os, 'replace', side_effect=fail_replace):
                    with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
                self.assertEqual(self.before, self.core.state_path().read_bytes())
                self.assertEqual(self.before, (writer.root() / p['id'] / 'state.before.json').read_bytes())
                self.assertEqual('not_applied', writer.recover(self.core, p['id'])['state'])

    def test_backup_fsync_failure_keeps_catalog_and_recovery_is_not_restore(self):
        p = self.preview(); original = writer.os.fsync
        def fail(fd):
            if os.readlink('/proc/self/fd/' + str(fd)).endswith('/state.before.json'):
                raise OSError('synthetic backup fsync failure')
            return original(fd)
        with patch.object(writer.os, 'fsync', side_effect=fail):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        self.assertEqual('not_applied', writer.recover(self.core, p['id'])['state'])

    def test_failed_completion_receipt_requires_recovery_after_committed_catalog(self):
        p = self.preview(); original = writer.atomic
        def fail(fd, name, raw):
            if name == 'transaction.json' and json.loads(raw)['state'] == 'succeeded':
                raise OSError('synthetic receipt failure')
            return original(fd, name, raw)
        with patch.object(writer, 'atomic', side_effect=fail):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertNotEqual(self.before, self.core.state_path().read_bytes())
        self.assertEqual('succeeded', writer.recover(self.core, p['id'])['state'])

    def test_new_job_during_apply_blocks_even_under_noncooperating_insertion(self):
        p = self.preview(); original = writer.write_exclusive
        job = self.f.home / '.local/share/myct/cloud-jobs' / ('c' * 32 + '.json')
        def insert(fd, name, raw):
            result = original(fd, name, raw)
            if name == 'state.before.json':
                job.write_bytes(writer.encoded({'id': 'c' * 32, 'state': 'uncertain'})); job.chmod(0o600)
            return result
        with patch.object(writer, 'write_exclusive', side_effect=insert):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())
        with self.assertRaisesRegex(writer.Error, 'CONFLICTING_JOB'): writer.recover(self.core, p['id'])

    def test_pending_operation_blocks_different_plan_until_explicit_recovery(self):
        p = self.preview(); original = writer.write_exclusive
        def fail(fd, name, raw):
            if name == 'state.before.json': raise OSError('fixture')
            return original(fd, name, raw)
        with patch.object(writer, 'write_exclusive', side_effect=fail):
            with self.assertRaises(writer.Error): self.apply(p)
        newer = self.preview()
        with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(newer)
        writer.recover(self.core, p['id'])
        self.assertEqual('succeeded', self.apply(newer)['state'])

    def test_lock_order_is_delivery_catalogs_sorted_naming_then_media(self):
        original = writer.parent; observed = []
        def observe(path, **kwargs):
            observed.append(str(path.relative_to(self.f.home)))
            return original(path, **kwargs)
        with patch.object(writer, 'parent', side_effect=observe), writer.locks(self.core, ['c' * 40, 'a' * 40]):
            pass
        self.assertEqual(['.local/share/myct/cloud-jobs/delivery.lock',
            '.local/share/myct/archive/catalog.lock', '.local/share/myct/archive-torrents/catalog.lock',
            '.local/share/myct/torrent-names/' + 'a' * 40 + '.lock',
            '.local/share/myct/torrent-names/' + 'c' * 40 + '.lock',
            '.local/share/myct/media/state.lock'], observed)

    def test_existing_operation_directory_and_redirected_backup_root_refuse(self):
        p = self.preview()
        operation = writer.root() / p['id']; operation.mkdir(parents=True, mode=0o700)
        marker = operation / 'preserve'; marker.write_bytes(b'never overwrite')
        with self.assertRaises(writer.Error): self.apply(p)
        self.assertEqual(b'never overwrite', marker.read_bytes())
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_symlink_backup_root_cannot_redirect_writes(self):
        p = self.preview(); destination = self.f.home / 'private-other'; destination.mkdir(mode=0o700)
        marker = destination / 'preserve'; marker.write_bytes(b'unchanged')
        writer.root().parent.mkdir(parents=True, mode=0o700)
        writer.root().symlink_to(destination, target_is_directory=True)
        with self.assertRaises((writer.Error, OSError)): self.apply(p)
        self.assertEqual(b'unchanged', marker.read_bytes())
        self.assertEqual([marker], list(destination.iterdir()))
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_change_after_committing_intent_is_caught_by_final_validation(self):
        p = self.preview(); original = writer.atomic
        src = next(self.f.source_root.iterdir())
        def change(fd, name, raw):
            result = original(fd, name, raw)
            if name == 'transaction.json' and json.loads(raw)['state'] == 'committing':
                src.write_bytes(b'changed at final validation')
            return result
        with patch.object(writer, 'atomic', side_effect=change):
            with self.assertRaisesRegex(writer.Error, 'RECOVERY_NEEDED'): self.apply(p)
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_cli_round_trip_and_errors_are_sanitized(self):
        plan = self.core.run(['reconcile-preview', '--provenance', str(self.proof)])
        file = self.f.home / 'plan.json'; file.write_bytes(writer.encoded(plan)); file.chmod(0o600)
        result = self.core.run(['reconcile-apply', '--plan', str(file), '--provenance', str(self.proof),
                                '--confirm', plan['id'], '--accept-legacy', plan['id']])
        self.assertEqual('succeeded', result['state'])
        self.assertNotIn(str(self.f.home), json.dumps(result))
        with self.assertRaises(self.core.MediaError) as error:
            self.core.run(['reconcile-apply', '--plan', str(file), '--provenance', str(self.proof), '--confirm', 'yes'])
        self.assertNotIn(str(self.f.home), str(error.exception))

    def test_unavailable_rpc_fails_closed_without_private_exception_text(self):
        with patch.object(self.core, 'read_torrents', side_effect=RuntimeError('SYNTHETIC-PRIVATE-TOKEN')):
            with self.assertRaisesRegex(writer.Error, 'RPC_UNAVAILABLE') as caught: self.preview()
        self.assertNotIn('SYNTHETIC', str(caught.exception))
        self.assertEqual(self.before, self.core.state_path().read_bytes())

    def test_media_writable_by_another_account_is_not_authorized(self):
        next(self.f.source_root.iterdir()).chmod(0o660)
        with self.assertRaisesRegex(writer.Error, 'UNSAFE_CONTENT'): self.preview()
        self.assertEqual(self.before, self.core.state_path().read_bytes())


if __name__ == '__main__': unittest.main()
