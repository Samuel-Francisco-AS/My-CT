"""R1 read-only space: real read() on disposable trees, fake Drive/RPC, controlled clock."""
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

    def test_space_measures_each_actual_tree_read_different_sizes_and_not_library_alias(self):
        f = self.fixture; identity = f.prepare()
        p = progress_fixture.ProgressTests(); p.setUp(); self.addCleanup(p.doCleanups)
        observed = []
        def capture(): observed.append(copy.deepcopy(p.progress.row))
        catalog = self.tool.common.state_dir() / 'catalog.json'
        before = catalog.read_bytes(); media_before = self.tool.media.state_path().read_bytes()
        with patch.object(self.tool.common, 'progress', p.progress), patch.object(p.progress, 'publish', side_effect=capture):
            result = f.preview(identity)
        reads = [r for r in observed if r['phase'] == 'local_read' and r['bytes_done'] > 0]
        self.assertEqual(2, len(reads))  # Original + independent restore, same content.
        expected = sum(v['size'] for v in f.row(identity)['manifest']['files'])
        self.assertEqual([expected, expected], [r['bytes_done'] for r in reads])
        self.assertEqual([1, 2], [r['read_pass'] for r in reads])
        self.assertTrue(len({v['size'] for v in f.row(identity)['manifest']['files']}) > 1)
        self.assertTrue(all(r['bytes_total'] == expected and r['percent'] is None for r in reads))
        self.assertFalse(any(r['phase'] == 'library_read' for r in observed))  # Known inode alias uses stat.
        self.assertTrue(result['can_release']); self.assertEqual([], f.mutations())
        self.assertEqual(before, catalog.read_bytes()); self.assertEqual(media_before, self.tool.media.state_path().read_bytes())
        phases = {r['phase'] for r in observed}
        self.assertTrue({'remote_check', 'transmission_check', 'library_inventory', 'finalizing'} <= phases)
        self.assertNotIn('phase_seconds', result)  # Never pollute the token's authoritative snapshot.
        self.assertNotIn(str(f.home), json.dumps(observed))

    def test_hardlink_only_read_and_failed_telemetry_cannot_mutate_catalog_or_bypass_divergence(self):
        f = self.fixture; identity = f.prepare(restored=False)
        # Remove only disposable original names. The library inode remains; no release operation.
        import shutil
        shutil.rmtree(f.base)
        p = progress_fixture.ProgressTests(); p.setUp(); self.addCleanup(p.doCleanups)
        observed = []
        original = p.progress.publish
        def publish():
            observed.append(copy.deepcopy(p.progress.row)); original()
        catalog = self.tool.common.state_dir() / 'catalog.json'; before = catalog.read_bytes()
        with patch.object(self.tool.common, 'progress', p.progress), patch.object(p.progress, 'publish', side_effect=publish), \
                patch('myct_cloud_progress.os.replace', side_effect=OSError('fixture disk full')):
            result = f.preview(identity)
        reads = [r for r in observed if r['phase'] == 'library_read' and r['bytes_done']]
        self.assertEqual([f.link.stat().st_size], [r['bytes_done'] for r in reads])
        self.assertEqual(before, catalog.read_bytes()); self.assertEqual([], f.mutations())
        self.assertTrue(f.link.exists()); self.assertFalse(any(r.get('percent') == 100 for r in observed))
        self.assertIsNone(read_progress(p.root, p.progress.identity))

    def test_telemetry_does_not_change_preview_token_or_release_revalidation_contract(self):
        f = self.fixture; identity = f.prepare()
        with patch.object(self.tool.time, 'time', return_value=100):
            plain = self.tool.storage_inventory(f.row(identity), f.engine)
            plan = f.preview(identity)
        self.assertEqual(self.tool.preview_token(plain, 100), plan['preview'])
        self.assertEqual([], f.mutations())
        # Remote corruption remains an authoritative blocker, not a telemetry success.
        f.cloud.mode = 'no_md5'
        blocked = f.preview(identity)
        self.assertFalse(blocked['can_release']); self.assertTrue(blocked['blockers'])

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
