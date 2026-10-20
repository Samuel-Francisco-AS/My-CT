"""C1C bridge only. Engines are fake; no personal catalogs, Drive, RPC or deletion."""
import base64
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'myct-cloud'
loader = importlib.machinery.SourceFileLoader('cloud_bridge_test', str(SOURCE))
spec = importlib.util.spec_from_loader(loader.name, loader)
cloud = importlib.util.module_from_spec(spec)
loader.exec_module(cloud)
ID = 'a' * 64
REQUEST = 'b' * 32
HASH = 'c' * 40
TOKEN = str(int(time.time())) + '-' + 'd' * 64
LIB = 'Filmes/Fixture/Fixture.mkv'


class CloudTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.patches = [patch.object(cloud, 'ROOT', self.root / 'jobs'), patch.object(cloud, 'HERE', self.root)]
        for p in self.patches: p.start()
        cloud.private_root()

    def tearDown(self):
        for p in self.patches: p.stop()
        self.tmp.cleanup()

    def work(self, action='restore', *tail):
        return {'kind': 'torrent', 'args': [action, HASH if action in ('archive', 'inspect') else ID, *tail]}

    def row(self, state='starting', work=None):
        return {'id': REQUEST, 'state': state, 'work': work or self.work(), 'created': time.time()}

    def test_fixed_actions_and_consent(self):
        for action in ('inspect', 'archive', 'space', 'restore', 'recover'):
            self.assertEqual(cloud.validate(self.work(action)), self.work(action))
        cloud.validate(self.work('recover', '--resume'))
        cloud.validate(self.work('relink', '--library', LIB))
        cloud.validate(self.work('release', '--preview', TOKEN, '--confirm', TOKEN))
        cloud.validate(self.work('release', '--preview', TOKEN, '--confirm', TOKEN, '--remove-library', LIB, '--confirm-library', TOKEN))

    def test_invalid_ids_paths_flags_and_missing_library_consent(self):
        bad = [self.work('shell'), self.work('space', '--resume'),
               self.work('recover', '--resume', '--resume'), self.work('relink', '--library', '../private'),
               self.work('release', '--preview', TOKEN, '--confirm', TOKEN, '--remove-library', LIB),
               self.work('release', '--preview', TOKEN, '--confirm', TOKEN + 'a'),
               {'kind': 'file', 'args': ['archive', '/a/../b']},
               {'kind': 'file', 'args': ['archive', '/a\nb']},
               {'kind': 'torrent', 'args': ['restore', '$(whoami)']}]
        for work in bad:
            with self.subTest(work=work), self.assertRaises(cloud.Failure): cloud.validate(work)

    def test_common_file_is_data_never_shell(self):
        path = "/home/user/a ' $(touch bad);.bin"
        cloud.validate({'kind': 'file', 'args': ['archive', path]})
        helper = self.root / 'myct-archive'
        helper.write_text('import json,sys\nprint(json.dumps({"ok":True,"args":sys.argv[1:]}))')
        result = cloud.invoke('file', ['archive', path])
        self.assertEqual(result['args'], ['archive', path])
        self.assertFalse((self.root / 'bad').exists())

    def test_missing_helper_and_bad_json(self):
        self.assertEqual(cloud.invoke('file', ['status'])['error']['code'], 'DEPENDENCIES')
        (self.root / 'myct-archive').write_text('print("private raw error")')
        result = cloud.invoke('file', ['status'])
        self.assertEqual(result['error']['code'], 'RESPONSE')
        self.assertNotIn('private raw', json.dumps(result))

    def test_cli_error_contract_preserved(self):
        (self.root / 'myct-archive').write_text('import json,sys\nprint(json.dumps({"ok":False,"error":{"code":"AUTH","message":"Drive indisponível"}}))\nsys.exit(2)')
        self.assertEqual(cloud.invoke('file', ['status'])['error']['code'], 'AUTH')

    def test_linger_absent_does_not_accept_or_launch(self):
        with patch.object(cloud.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'no\n')) as run:
            with self.assertRaisesRegex(cloud.Failure, 'Persistência'): cloud.start(REQUEST, self.work())
            self.assertEqual(run.call_count, 1)
        self.assertFalse(list(cloud.ROOT.glob('*.json')))

    def test_durable_delivery_and_duplicate_request(self):
        calls = []
        def run(args, **kw):
            calls.append(args)
            if args[0] == 'loginctl': return subprocess.CompletedProcess(args, 0, 'yes\n')
            self.assertEqual(cloud.load(REQUEST)['state'], 'starting')
            self.assertEqual(args[-2:], ['_work', REQUEST])
            self.assertIn('--user', args)
            return subprocess.CompletedProcess(args, 0)
        with patch.object(cloud.subprocess, 'run', side_effect=run):
            first = cloud.start(REQUEST, self.work())
            second = cloud.start(REQUEST, self.work())
        self.assertFalse(first['duplicate']); self.assertTrue(second['duplicate'])
        self.assertEqual(len(calls), 2)
        self.assertEqual((cloud.ROOT / (REQUEST + '.json')).stat().st_mode & 0o777, 0o600)
        self.assertEqual(cloud.ROOT.stat().st_mode & 0o777, 0o700)

    def test_concurrent_active_and_uncertain_block_new_delivery(self):
        for state in ('starting', 'running', 'uncertain'):
            cloud.save(self.row(state))
            with patch.object(cloud.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'active\n')) as run:
                with self.assertRaises(cloud.Failure): cloud.start('e' * 32, self.work())
                self.assertFalse(any(c.args[0][0] == 'systemd-run' for c in run.call_args_list))

    def test_launch_timeout_is_uncertain_never_replayed(self):
        with patch.object(cloud.subprocess, 'run', side_effect=[subprocess.CompletedProcess([], 0, 'yes\n'), subprocess.TimeoutExpired('systemd-run', 8)]):
            self.assertEqual(cloud.start(REQUEST, self.work())['task']['state'], 'uncertain')
        with patch.object(cloud.subprocess, 'run') as run:
            self.assertTrue(cloud.start(REQUEST, self.work())['duplicate'])
            run.assert_not_called()

    def test_worker_runs_exactly_once_and_stores_original_json(self):
        cloud.save(self.row())
        reply = {'ok': True, 'id': ID, 'state': 'verified', 'verified': ['size', 'md5', 'sha256']}
        with patch.object(cloud, 'invoke', return_value=reply) as invoke:
            cloud.worker(REQUEST); cloud.worker(REQUEST)
            invoke.assert_called_once_with('torrent', ['restore', ID], REQUEST)
        row = cloud.load(REQUEST)
        self.assertEqual(row['state'], 'succeeded'); self.assertEqual(row['result'], reply)

    def test_worker_failure_and_crash_are_not_success(self):
        for outcome in (False, None):
            cloud.save(self.row())
            with patch.object(cloud, 'invoke', **({'return_value': {'ok': False, 'error': {'code': 'NETWORK'}}} if outcome is False else {'side_effect': OSError()})):
                cloud.worker(REQUEST)
            self.assertEqual(cloud.load(REQUEST)['state'], 'failed' if outcome is False else 'uncertain')

    def test_disappeared_unit_after_reboot_is_uncertain(self):
        row = self.row('running'); row['created'] -= 60; cloud.save(row)
        with patch.object(cloud.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'inactive\n')):
            self.assertEqual(cloud.reconcile(row)['state'], 'uncertain')
        self.assertEqual(cloud.load(REQUEST)['state'], 'uncertain')

    def test_worker_finishing_during_query_does_not_become_uncertain(self):
        row = self.row('running'); cloud.save(row)
        def run(*args, **kwargs):
            done = {**row, 'state': 'succeeded', 'result': {'ok': True}}
            cloud.save(done)
            return subprocess.CompletedProcess([], 0, 'inactive\n')
        with patch.object(cloud.subprocess, 'run', side_effect=run):
            self.assertEqual(cloud.reconcile(row)['state'], 'succeeded')

    def test_snapshot_projects_real_catalog_and_busy_without_copying_journal_plan(self):
        mf = {'name': 'Fixture', 'infohash': HASH, 'total_size': 42, 'library_links': [], 'original_download_dir': '/nonexistent-fixture'}
        def invoke(kind, args):
            if kind == 'torrent': return {'ok': True, 'entries': [{'id': ID, 'manifest': mf, 'state': 'verified', 'release': {'state': 'released', 'plan': 'huge private inventory'}}]}
            if kind == 'file': return {'ok': False, 'error': {'code': 'BUSY'}}
            return {'ok': True, 'torrents': []}
        with patch.object(cloud, 'invoke', side_effect=invoke): result = cloud.snapshot()
        entry = result['catalogs']['torrent']['entries'][0]
        self.assertEqual(entry['name'], 'Fixture'); self.assertEqual(entry['release'], {'state': 'released'})
        self.assertFalse(entry['local_present']); self.assertFalse(result['catalogs']['file']['ok'])

    def test_live_link_projection_handles_missing_original_and_restored_hardlink(self):
        original = self.root / 'absent'
        restored = self.root / 'restored'; restored.mkdir()
        (restored / 'video.mkv').write_bytes(b'fixture')
        library = self.root / 'Media' / LIB; library.parent.mkdir(parents=True)
        os.link(restored / 'video.mkv', library)
        # A link created after the upload comes from the engine's live resolver.
        link = {'path': LIB, 'source': 'video.mkv'}
        row = {'id': ID, 'state': 'verified', 'manifest': {'name': 'Fixture', 'infohash': HASH,
               'total_size': 7, 'library_links': [], 'original_download_dir': str(original)},
               'restore': {'state': 'verified', 'path': str(restored)}}
        def invoke(kind, args):
            return {'ok': True, 'entries': [row] if kind == 'torrent' else [], 'torrents': []}
        with patch.object(cloud, 'invoke', side_effect=invoke), patch.object(cloud, 'live_library', return_value=([link], True)), patch.object(cloud.Path, 'home', return_value=self.root):
            entry = cloud.snapshot()['catalogs']['torrent']['entries'][0]
        self.assertTrue(entry['library_checked']); self.assertTrue(entry['local_present'])
        self.assertEqual(entry['library_existing'], 1); self.assertEqual(entry['library_linked'], 1)

    def test_base64_request_decoding_and_invalid_request_id(self):
        work = self.work()
        payload = base64.urlsafe_b64encode(json.dumps(work).encode()).decode().rstrip('=')
        with patch.object(cloud, 'start', return_value={'ok': True}) as start:
            cloud.run(['start', REQUEST, payload]); start.assert_called_once_with(REQUEST, work)
        with self.assertRaises(cloud.Failure): cloud.load('../private')

    def test_real_transient_unit_survives_client_exit_with_isolated_fake_engine(self):
        # All work/catalog paths belong to this TemporaryDirectory; no real engines.
        check = subprocess.run(['loginctl', 'show-user', str(os.getuid()), '-p', 'Linger', '--value'], capture_output=True, text=True)
        manager = subprocess.run(['systemctl', '--user', 'is-system-running'], capture_output=True, text=True)
        if check.stdout.strip() != 'yes' or manager.stdout.strip() not in ('running', 'degraded'):
            self.skipTest('User manager/linger unavailable for real transient-unit test')
        bridge = self.root / 'myct-cloud'
        source = SOURCE.read_text().replace("ROOT = Path.home() / '.local/share/myct/cloud-jobs'", 'ROOT = Path(' + repr(str(cloud.ROOT)) + ')')
        bridge.write_text(source)
        (self.root / 'myct_cloud_progress.py').write_text((SOURCE.parent / 'myct_cloud_progress.py').read_text())
        marker = self.root / 'engine-started'
        engine = self.root / 'myct-archive-torrent'
        engine.write_text('import json,time,os\nfrom pathlib import Path\nfrom myct_cloud_progress import Progress\np=Path(' + repr(str(marker)) + ')\nwith p.open("a") as f: f.write("once\\n")\nprogress=Progress(Path(' + repr(str(cloud.ROOT)) + '),os.environ["MYCT_CLOUD_JOB"])\nprogress.plan([100]); progress.begin_copy(False)\nprogress.stats(json.dumps({"stats":{"bytes":50,"totalBytes":100,"speed":25,"eta":2}}))\ntime.sleep(2)\nprogress.stats(json.dumps({"stats":{"bytes":100,"totalBytes":100,"speed":25,"eta":0}}))\nprogress.end_copy()\nprint(json.dumps({"ok":True,"state":"verified"}))\n')
        payload = base64.urlsafe_b64encode(json.dumps(self.work()).encode()).decode().rstrip('=')
        try:
            start = subprocess.run([sys.executable, str(bridge), 'start', REQUEST, payload], capture_output=True, text=True, timeout=15)
            self.assertEqual(start.returncode, 0, start.stdout)
            self.assertTrue(json.loads(start.stdout)['ok'])
            # The delivery process has exited; a new query observes persistent telemetry.
            progress_path = cloud.ROOT / (REQUEST + '.progress')
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                current = cloud.run(['task', REQUEST])['task']
                if current.get('progress', {}).get('bytes_done') == 50: break
                time.sleep(.02)
            self.assertEqual(current['progress']['bytes_done'], 50)
            self.assertEqual(current['progress']['percent'], 50)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                row = cloud.load(REQUEST)
                if row['state'] in ('succeeded', 'failed', 'uncertain'): break
                time.sleep(.05)
            self.assertEqual(row['state'], 'succeeded')
            self.assertEqual(row['result'], {'ok': True, 'state': 'verified'})
            self.assertEqual(cloud.run(['task', REQUEST])['task']['progress']['phase'], 'done')
            repeat = subprocess.run([sys.executable, str(bridge), 'start', REQUEST, payload], capture_output=True, text=True, timeout=15)
            self.assertTrue(json.loads(repeat.stdout)['duplicate'])
            self.assertEqual(marker.read_text(), 'once\n')
        finally:
            subprocess.run(['systemctl', '--user', 'stop', 'myct-cloud-' + REQUEST + '.service'], capture_output=True)


if __name__ == '__main__':
    unittest.main()
