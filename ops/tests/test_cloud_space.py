"""R1 metadata-only space and single-pass release on disposable trees, fake Drive/RPC."""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import test_cloud_progress as progress_fixture
from myct_cloud_progress import SpaceProgress, read_progress
import test_archive_lifecycle as lifecycle


class SpaceMeterTests(unittest.TestCase):
    def setUp(self):
        fixture = progress_fixture.ProgressTests()
        fixture.setUp(); self.addCleanup(fixture.doCleanups)
        self.progress = fixture.progress; self.root = fixture.root
    def test_real_read_samples_are_monotonic_throttled_and_phase_scoped(self):
        now = [0.0]
        meter = SpaceProgress(self.progress, lambda: now[0])
        meter.reading(40 * 1024 * 1024)
        with patch.object(self.progress, 'publish', wraps=self.progress.publish) as publish:
            for i in range(30):
                now[0] += .25
                meter.advance(1024 * 1024)
                if i < 23: self.assertIsNone(self.progress.row['eta_seconds'])
            self.assertEqual(3, publish.call_count)  # 30 block reads, only 3 publications.
        saved = read_progress(self.root, self.progress.identity)
        self.assertEqual('read', saved['metric']); self.assertEqual('phase', saved['scope'])
        self.assertEqual(24 * 1024 * 1024, saved['bytes_done'])
        self.assertEqual(60, saved['percent']); self.assertEqual(4, saved['eta_seconds'])
        meter.end_read()
        self.assertEqual(30 * 1024 * 1024, read_progress(self.root, self.progress.identity)['bytes_done'])
        meter.phase('transmission_check')
        self.assertIsNone(self.progress.row['percent']); self.assertIsNone(self.progress.row['eta_seconds'])
        self.assertEqual(7.5, self.progress.row['phase_seconds']['local_read'])
        self.progress.finish(True)
        self.assertEqual('done', self.progress.row['phase']); self.assertIsNone(self.progress.row['percent'])

    def test_metadata_progress_has_no_content_percentage_and_cannot_report_reads(self):
        meter = SpaceProgress(self.progress, metric='metadata')
        meter.phase('local_inventory')
        row = read_progress(self.root, self.progress.identity)
        self.assertEqual('metadata', row['metric']); self.assertEqual(0, row['bytes_done'])
        self.assertIsNone(row['bytes_total']); self.assertIsNone(row['percent'])
        with self.assertRaises(ValueError): meter.reading(123)
        self.progress.finish(True)
        self.assertIsNone(read_progress(self.root, self.progress.identity)['percent'])

    def test_reader_keeps_atomic_snapshot_replaced_after_open_and_rejects_symlink_and_extra_link(self):
        import os
        self.progress.phase('preparing')
        path = self.root / (self.progress.identity + '.progress')
        original = os.fstat
        def replaced(fd):
            self.progress.phase('finalizing')
            return original(fd)  # Already-open snapshot now has nlink=0.
        with patch('myct_cloud_progress.os.fstat', side_effect=replaced):
            self.assertEqual('preparing', read_progress(self.root, self.progress.identity)['phase'])
        alias = self.root / 'alias'; os.link(path, alias)
        self.assertIsNone(read_progress(self.root, self.progress.identity))
        alias.unlink(); path.unlink(); path.symlink_to(alias)
        self.assertIsNone(read_progress(self.root, self.progress.identity))

    def test_unknown_zero_growing_total_unstable_rate_failure_and_interruption_no_false_success(self):
        now = [0.0]; meter = SpaceProgress(self.progress, lambda: now[0])
        for phase in ('remote_check', 'local_inventory', 'library_inventory', 'transmission_check', 'finalizing'):
            meter.phase(phase)
            row = read_progress(self.root, self.progress.identity)
            self.assertIsNone(row['percent']); self.assertIsNone(row['bytes_total']); self.assertIsNone(row['eta_seconds'])
        meter.reading(1000)
        for size in (10, 200, 10):
            now[0] += 2; meter.advance(size)
        self.assertIsNone(self.progress.row['eta_seconds'])
        meter.advance(1000)
        self.assertIsNone(self.progress.row['bytes_total']); self.assertIsNone(self.progress.row['percent'])
        self.progress.finish(False)
        self.assertEqual('failed', self.progress.row['phase']); self.assertIsNone(self.progress.row['percent'])


class SpaceEngineTests(unittest.TestCase):
    def setUp(self):
        self.fixture = lifecycle.LifecycleTest()
        self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)
        self.tool = lifecycle.tool

    def thirteen(self):
        import os
        f = self.fixture; f.linked()
        state = self.tool.media.load_state()
        paths = [f.rel]
        for n in range(2, 14):
            src = f.base / ('Pacote/extra%02d.mkv' % n)
            src.write_bytes(b'video fixture ' * n)
            rel = 'Series/Fixture/Season 01/E%02d.mkv' % n
            dst = f.home / 'Media' / rel
            dst.parent.mkdir(parents=True, exist_ok=True); os.link(src, dst)
            state['links'][rel] = {'id': f.key + ':' + str(src.relative_to(f.base)),
                                   'dev': dst.stat().st_dev, 'ino': dst.stat().st_ino}
            paths.append(rel)
        self.tool.media.save_state(state)
        identity = f.archived(); f.engine.calls.clear(); f.cloud.calls.clear()
        return identity, sorted(paths), state

    def test_thirteen_links_complete_after_first_last_or_multiple_selection_blocks(self):
        identity, paths, original = self.thirteen()
        f = self.fixture
        for bad in ([paths[0]], [paths[-1]], paths[::2]):
            state = copy.deepcopy(original)
            for rel in bad: state['links'][rel]['dev'] -= 1
            self.tool.media.save_state(state)
            before = self.tool.media.state_path().read_bytes()
            catalog = (self.tool.common.state_dir() / 'catalog.json').read_bytes()
            plan = f.run_cli('space', identity, *[arg for rel in paths for arg in ('--remove-library', rel)])
            self.assertEqual(paths, plan['remove_library'])
            self.assertEqual(13, len(plan['library']))
            self.assertTrue(plan['library_complete'])
            self.assertEqual(set(paths), {r['path'] for r in plan['library']})
            self.assertEqual(len(bad), sum(r['status'] == 'divergent' for r in plan['library']))
            self.assertEqual(13 - len(bad), sum(r['managed'] for r in plan['library']))
            self.assertIn('LIBRARY_SELECTION', plan['blockers']); self.assertFalse(plan['can_release'])
            self.assertEqual(before, self.tool.media.state_path().read_bytes())
            self.assertEqual(catalog, (self.tool.common.state_dir() / 'catalog.json').read_bytes())
            self.assertEqual([], f.mutations())

    def test_failed_library_scan_reports_known_paths_and_explicit_incompleteness(self):
        identity, paths, _ = self.thirteen(); f = self.fixture
        original = self.tool.check_mounts
        def mounts(root):
            if root == self.tool.media.media_root(): raise OSError('fixture scan failure')
            return original(root)
        with patch.object(self.tool, 'check_mounts', side_effect=mounts):
            plan = f.preview(identity)
        self.assertEqual(13, len(plan['library']))
        self.assertEqual(set(paths), {r['path'] for r in plan['library']})
        self.assertFalse(plan['library_complete']); self.assertIn('LIBRARY_INCOMPLETE', plan['blockers'])
        self.assertFalse(plan['can_release']); self.assertEqual([], f.mutations())

    def test_missing_unmanaged_and_unsafe_links_have_distinct_statuses(self):
        identity, paths, state = self.thirteen(); f = self.fixture
        absent, unmanaged, unsafe = paths[:3]
        (f.home / 'Media' / absent).unlink()
        state['links'].pop(unmanaged)
        target = f.home / 'Media' / unsafe
        target.unlink(); target.symlink_to(f.base / 'Pacote/filme.mkv')
        self.tool.media.save_state(state)
        plan = f.preview(identity)
        statuses = {r['path']: r['status'] for r in plan['library']}
        self.assertEqual('absent', statuses[absent]); self.assertEqual('unmanaged', statuses[unmanaged])
        self.assertEqual('uninspectable', statuses[unsafe]); self.assertEqual(13, len(statuses))
        self.assertFalse(plan['library_complete']); self.assertFalse(plan['can_release'])

    def test_media_state_failure_keeps_original_manifest_paths(self):
        identity, paths, _ = self.thirteen(); f = self.fixture
        with patch.object(self.tool.media, 'load_state', side_effect=self.tool.media.MediaError('fixture invalid state')):
            plan = f.preview(identity)
        self.assertEqual(set(paths), {r['path'] for r in plan['library']})
        self.assertFalse(plan['library_complete']); self.assertFalse(plan['can_release'])

    def test_one_uninspectable_file_does_not_stop_remaining_scan(self):
        identity, paths, _ = self.thirteen(); f = self.fixture
        failed = f.home / 'Media' / paths[0]
        original = self.tool.media.record_matches
        def check(info, key, path, source=None):
            if path == failed: raise OSError('fixture one file unavailable')
            return original(info, key, path, source)
        with patch.object(self.tool.media, 'record_matches', side_effect=check):
            plan = f.preview(identity)
        self.assertEqual(13, len(plan['library']))
        self.assertEqual(12, sum(r['managed'] for r in plan['library']))
        self.assertFalse(plan['library_complete']); self.assertFalse(plan['can_release'])

    def test_space_never_reads_payload_or_calls_drive_and_token_is_selection_independent(self):
        import builtins
        import io
        import os
        f = self.fixture; identity = f.prepare()
        payloads = {p.resolve() for root in (f.base, self.tool.restored_path(f.row(identity)), f.home / 'Media')
                    for p in root.rglob('*') if p.is_file()}
        # Block both high-level opens and descriptor reads of payloads. Metadata
        # ioctls may open a descriptor, but cannot read any byte from it.
        opened = set()
        real_open, real_io, real_os, real_close = builtins.open, io.open, os.open, os.close
        def high_level(original):
            def guarded(path, *args, **kwargs):
                if not isinstance(path, int) and Path(path).resolve() in payloads:
                    raise AssertionError('payload open during preview')
                return original(path, *args, **kwargs)
            return guarded
        def fd_open(path, *args, **kwargs):
            fd = real_os(path, *args, **kwargs)
            if not isinstance(path, int) and Path(path).resolve() in payloads:
                opened.add(fd)
            return fd
        def close(fd):
            opened.discard(fd)
            return real_close(fd)
        def fd_guard(original):
            def guarded(fd, *args, **kwargs):
                if fd in opened: raise AssertionError('payload read during preview')
                return original(fd, *args, **kwargs)
            return guarded
        observed = []
        with patch.object(self.tool.common, 'fingerprint', side_effect=AssertionError('hash in space')), \
                patch.object(self.tool.common, 'rclone', side_effect=AssertionError('Drive in space')), \
                patch.object(self.tool.common.progress, 'publish', side_effect=lambda: observed.append(copy.deepcopy(self.tool.common.progress.row))), \
                patch('builtins.open', high_level(real_open)), patch('io.open', high_level(real_io)), \
                patch('os.open', side_effect=fd_open), patch('os.close', side_effect=close), patch('os.read', fd_guard(os.read)), \
                patch('os.pread', fd_guard(os.pread)), patch.object(self.tool.time, 'time', return_value=100):
            plain = f.preview(identity)
            selected = f.preview(identity, True)
        self.assertEqual(plain['preview'], selected['preview'])
        self.assertEqual(2, plain['version']); self.assertTrue(plain['library_complete'])
        self.assertFalse(plain['content_verified']); self.assertFalse(plain['remote']['revalidated'])
        self.assertTrue(selected['can_release']); self.assertEqual([], f.mutations())
        self.assertNotEqual(plain['remove_paths'], selected['remove_paths'])
        self.assertEqual(0, plain['reclaim_guaranteed_bytes'])
        self.assertFalse(any(r.get('metric') == 'metadata' and r.get('bytes_done') for r in observed))

    def test_no_original_uses_saved_identity_and_unproven_replacement_stays_protected_without_hash(self):
        import shutil
        f = self.fixture; identity = f.prepare(restored=False)
        shutil.rmtree(f.base)
        f.engine.row = None
        with patch.object(self.tool.common, 'fingerprint', side_effect=AssertionError('hidden fallback hash')):
            result = f.preview(identity, True)
            self.assertTrue(result['can_release'], result['blockers'])
            raw = f.link.read_bytes(); f.link.unlink(); f.link.write_bytes(raw)
            blocked = f.preview(identity, True)
        self.assertFalse(blocked['can_release'])
        self.assertIn('LIBRARY_SELECTION', blocked['blockers'])
        self.assertFalse(blocked['library'][0]['linked_to_package'])
        self.assertEqual([], f.mutations())

    def test_release_reads_each_inode_once_and_remote_once_with_locally_changed_selection(self):
        f = self.fixture; identity = f.prepare()
        plan = f.preview(identity)  # No links selected at query time.
        unique = len(plan['inventory']['inodes'])
        original = self.tool.common.fingerprint
        reads = []
        def fingerprint(path, **kwargs):
            inode = (path.stat().st_dev, path.stat().st_ino)
            self.assertNotIn(inode, reads); reads.append(inode)
            return original(path, **kwargs)
        p = progress_fixture.ProgressTests(); p.setUp(); self.addCleanup(p.doCleanups)
        observed = []
        with patch.object(self.tool.common, 'progress', p.progress), \
                patch.object(p.progress, 'publish', side_effect=lambda: observed.append(copy.deepcopy(p.progress.row))), \
                patch.object(self.tool.common, 'fingerprint', side_effect=fingerprint), \
                patch.object(self.tool, 'remote_objects', wraps=self.tool.remote_objects) as remote:
            f.apply(identity, plan, True)  # Same token, new explicit selection.
        self.assertEqual(unique, len(reads)); self.assertEqual(1, remote.call_count)
        phases = {r['phase'] for r in observed}
        self.assertTrue({'local_read', 'remote_check', 'pausing', 'removing_entry', 'unlinking'} <= phases)
        read = [r for r in observed if r['phase'] == 'local_read' and r['bytes_done']]
        self.assertEqual(sum(i['logical_bytes'] for i in plan['inventory']['inodes']), read[-1]['bytes_done'])
        self.assertFalse(f.link.exists()); self.assertFalse(f.base.exists())

    def test_same_size_content_change_is_detected_only_at_execution_before_any_rpc_mutation(self):
        f = self.fixture; identity = f.prepare()
        file = f.base / 'Pacote/legenda.srt'
        file.write_bytes(b'x' * file.stat().st_size)
        plan = f.preview(identity)
        self.assertTrue(plan['can_release'])  # Metadata preview does not claim content integrity.
        f.assertFailure('SOURCE_CHANGED', f.apply, identity, plan)
        self.assertTrue(file.exists()); self.assertEqual([], f.mutations())

    def test_metadata_recheck_catches_same_size_change_after_hash_without_rereading(self):
        import os
        f = self.fixture; identity = f.prepare(); plan = f.preview(identity)
        original = self.tool.remote_objects
        target = f.base / 'Pacote/legenda.srt'
        def change(row):
            result = original(row)
            before = target.stat()
            target.write_bytes(b'x' * before.st_size)
            os.utime(target, ns=(before.st_atime_ns, before.st_mtime_ns))
            return result
        with patch.object(self.tool, 'remote_objects', side_effect=change):
            f.assertFailure('STATE_CHANGED', f.apply, identity, plan)
        self.assertTrue(target.exists()); self.assertEqual([], f.mutations())

    def test_telemetry_does_not_change_preview_token_and_remote_is_checked_at_execution(self):
        f = self.fixture; identity = f.prepare()
        with patch.object(self.tool.time, 'time', return_value=100):
            plain = self.tool.storage_inventory(f.row(identity), f.engine)
            plan = f.preview(identity)
        self.assertEqual(self.tool.preview_token(plain, 100), plan['preview'])
        self.assertEqual([], f.mutations())
        f.cloud.mode = 'no_md5'
        fresh = f.preview(identity)
        self.assertTrue(fresh['can_release'])
        f.assertFailure('VERIFICATION_UNAVAILABLE', f.apply, identity, fresh)
        self.assertEqual([], f.mutations())

class PersistentSpaceWorkerTests(unittest.TestCase):
    def test_space_worker_keeps_request_progress_and_result_without_live_client(self):
        import os
        import subprocess
        import sys
        import time
        import test_cloud as bridge_fixture
        f = bridge_fixture.CloudTests(); f.setUp(); self.addCleanup(f.tearDown)
        cloud = bridge_fixture.cloud
        bridge = f.root / 'myct-cloud'
        bridge.write_text(bridge_fixture.SOURCE.read_text().replace(
            "ROOT = Path.home() / '.local/share/myct/cloud-jobs'", 'ROOT = Path(' + repr(str(cloud.ROOT)) + ')'))
        (f.root / 'myct_cloud_progress.py').write_bytes((bridge_fixture.SOURCE.parent / 'myct_cloud_progress.py').read_bytes())
        source = f.root / 'fixture'; source.write_bytes(b'actual fixture bytes')
        ready = f.root / 'ready'; finish = f.root / 'finish'
        paths = ['Series/Fixture/E%02d.mkv' % i for i in range(1, 14)]
        work = f.work('space', *[s for p in paths for s in ('--remove-library', p)])
        cloud.save({'id': bridge_fixture.REQUEST, 'work': work, 'state': 'starting', 'created': time.time()})
        script = '''import json,os,time
from pathlib import Path
from myct_cloud_progress import Progress,SpaceProgress
root=Path(ROOT)
p=Progress(root,os.environ['MYCT_CLOUD_JOB'])
m=SpaceProgress(p);m.reading(Path(SOURCE).stat().st_size)
with Path(SOURCE).open('rb') as stream:
 m.advance(len(stream.read(7)))
m.end_read();Path(READY).touch()
deadline=time.monotonic()+5
while not Path(FINISH).exists() and time.monotonic()<deadline: time.sleep(.01)
with Path(SOURCE).open('rb') as stream:
 stream.read(7);m.advance(len(stream.read()))
m.end_read();m.phase('finalizing')
print(json.dumps({'ok':True,'remove_library':PATHS}))
'''
        for name, value in [('ROOT', str(cloud.ROOT)), ('SOURCE', str(source)), ('READY', str(ready)), ('FINISH', str(finish)), ('PATHS', paths)]:
            script = script.replace(name, repr(value))
        (f.root / 'myct-archive-torrent').write_text(script)
        worker = subprocess.Popen([sys.executable, str(bridge), '_work', bridge_fixture.REQUEST], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 4
            while not ready.exists() and worker.poll() is None and time.monotonic() < deadline: time.sleep(.01)
            self.assertTrue(ready.exists())
            # Simulate a new Android client querying an already running persistent worker.
            with patch.object(cloud.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'active\n')):
                current = cloud.run(['task', bridge_fixture.REQUEST])['task']
            self.assertEqual(work, current['work']); self.assertEqual(13, len(current['work']['args'][2:]) // 2)
            self.assertEqual(7, current['progress']['bytes_done']); self.assertEqual('read', current['progress']['metric'])
            self.assertFalse(current.get('cancel_supported', False))
            finish.touch(); out, err = worker.communicate(timeout=5)
            self.assertEqual(0, worker.returncode, err)
            final = cloud.load(bridge_fixture.REQUEST)
            self.assertEqual('succeeded', final['state']); self.assertEqual(paths, final['result']['remove_library'])
            telemetry = read_progress(cloud.ROOT, bridge_fixture.REQUEST)
            self.assertEqual('done', telemetry['phase']); self.assertIsNone(telemetry['percent'])
        finally:
            if worker.poll() is None: worker.kill(); worker.communicate()


if __name__ == '__main__': unittest.main()
