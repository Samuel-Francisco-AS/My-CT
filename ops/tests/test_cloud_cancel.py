"""Cancellation only of isolated jobs; never personal transfers/helpers."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
from unittest.mock import patch
import test_cloud as fixture

cloud = fixture.cloud
JOB = '6' * 32


class CancelTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.CloudTests(); self.fixture.setUp(); self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.root

    def row(self, state='starting', action='archive'):
        return {'id': JOB, 'work': self.fixture.work(action), 'state': state, 'created': time.time(),
                'bridge_version': 2, 'cancel_supported': True, 'worker_pid': 12345}

    def test_rejects_invalid_identity_noncopy_legacy_and_nonprivate_receipt(self):
        with patch.object(cloud.subprocess, 'run') as run:
            for bad in ('../private', JOB + '.service', '--all'):
                with self.assertRaises(cloud.Failure): cloud.cancel(bad)
            for action in ('release', 'recover', 'space', 'inspect'):
                row = self.row(action=action)
                if action == 'release': row['work'] = self.fixture.work('release', '--preview', fixture.TOKEN, '--confirm', fixture.TOKEN)
                cloud.save(row)
                with self.assertRaises(cloud.Failure): cloud.cancel(JOB)
            row = self.row(); row.pop('bridge_version'); cloud.save(row)
            with self.assertRaises(cloud.Failure): cloud.cancel(JOB)
            cloud.save(self.row()); path = cloud.ROOT / (JOB + '.json'); path.chmod(0o644)
            with self.assertRaises(cloud.Failure): cloud.cancel(JOB)
            run.assert_not_called()

    def test_starting_cancel_is_persistent_idempotent_and_worker_never_copies(self):
        cloud.save(self.row())
        with patch.object(cloud.subprocess, 'run') as run:
            first = cloud.cancel(JOB); second = cloud.cancel(JOB)
            self.assertTrue(first['task']['cancel_requested']); self.assertTrue(second['task']['cancel_requested'])
            run.assert_not_called()
        with patch.object(cloud, 'invoke') as invoke:
            cloud.worker(JOB); invoke.assert_not_called()
        self.assertEqual(cloud.load(JOB)['state'], 'cancelling')
        with patch.object(cloud.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'active\n')):
            self.assertEqual(cloud.run(['task', JOB])['task']['state'], 'cancelling')
        with patch.object(cloud.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'inactive\n')):
            self.assertEqual(cloud.run(['task', JOB])['task']['state'], 'cancelled')

    def test_exact_unit_process_and_intent_before_stop_without_claiming_cancelled(self):
        cloud.save(self.row('running'))
        expected = [sys.executable, str(cloud.HERE / 'myct-cloud'), '_work', JOB]
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            if 'show' in args:
                return subprocess.CompletedProcess(args, 0, 'Id=' + cloud.unit(JOB) + '\nMainPID=12345\nKillMode=control-group\n')
            self.assertTrue(cloud.load(JOB)['cancel_requested'])
            self.assertEqual(args, ['systemctl', '--user', 'stop', '--no-block', cloud.unit(JOB)])
            return subprocess.CompletedProcess(args, 0)
        with patch.object(cloud.subprocess, 'run', side_effect=run), patch.object(cloud.Path, 'read_bytes', return_value='\0'.join(expected).encode() + b'\0'):
            row = cloud.cancel(JOB)['task']; cloud.cancel(JOB)
        self.assertEqual(row['state'], 'running'); self.assertTrue(row['cancel_requested'])
        self.assertEqual(len(calls), 2)

    def test_wrong_unit_or_pid_never_stopped_and_ambiguous_stop_blocks_replay(self):
        cloud.save(self.row('running'))
        with patch.object(cloud.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'Id=unrelated.service\nMainPID=12345\nKillMode=control-group\n')) as run:
            with self.assertRaises(cloud.Failure): cloud.cancel(JOB)
            self.assertEqual(run.call_count, 1)
        cloud.save(self.row('running'))
        expected = [sys.executable, str(cloud.HERE / 'myct-cloud'), '_work', JOB]
        with patch.object(cloud.Path, 'read_bytes', return_value='\0'.join(expected).encode()), patch.object(cloud.subprocess, 'run', side_effect=[
                subprocess.CompletedProcess([], 0, 'Id=' + cloud.unit(JOB) + '\nMainPID=12345\nKillMode=control-group\n'), subprocess.TimeoutExpired('stop', 5)]):
            self.assertEqual(cloud.cancel(JOB)['task']['state'], 'uncertain')
        with patch.object(cloud.subprocess, 'run') as run:
            cloud.cancel(JOB); run.assert_not_called()

    def test_success_wins_race_and_missing_result_is_uncertain(self):
        for reply, expected in [({'ok': True}, 'succeeded'), ({'ok': False, 'error': {'code': 'RESPONSE'}}, 'uncertain')]:
            cloud.save(self.row())
            def invoke(*args):
                row = cloud.load(JOB); row['cancel_requested'] = True; cloud.save(row)
                return reply
            with patch.object(cloud, 'invoke', side_effect=invoke): cloud.worker(JOB)
            self.assertEqual(cloud.load(JOB)['state'], expected)
            with patch.object(cloud.subprocess, 'run') as run:
                cloud.cancel(JOB); run.assert_not_called()

    def test_real_file_engine_sigterm_during_upload_and_download_preserves_original(self):
        import signal
        import test_archive as archive_fixture
        for action in ('archive', 'restore'):
            with self.subTest(action=action):
                sample = archive_fixture.ArchiveTest(); sample.setUp()
                try:
                    target = str(sample.source) if action == 'archive' else sample.archived()['id']
                    root = sample.home / '.local/share/myct/cloud-jobs'; root.mkdir(parents=True, mode=0o700)
                    receipt = root / (JOB + '.json')
                    receipt.write_text(json.dumps({'state': 'running', 'worker_pid': os.getpid()})); receipt.chmod(0o600)
                    sample.mode('wait')
                    process = subprocess.Popen([sys.executable, str(archive_fixture.SCRIPT), action, target],
                                               env={**sample.env, 'MYCT_CLOUD_JOB': JOB},
                                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                    try:
                        deadline = time.monotonic() + 5
                        while not (sample.home / 'ready').exists() and time.monotonic() < deadline: time.sleep(.02)
                        self.assertTrue((sample.home / 'ready').exists())
                        child = int((sample.home / 'ready').read_text())
                        process.send_signal(signal.SIGTERM)
                        output, errors = process.communicate(timeout=7)
                        self.assertEqual(errors, '')
                        self.assertEqual(json.loads(output)['error']['code'], 'INTERRUPTED')
                        self.assertEqual(sample.source.read_bytes(), sample.original)
                        row = next(iter(sample.entries().values()))
                        if action == 'restore':
                            self.assertEqual(row['restore']['state'], 'failed')
                            self.assertFalse(Path(row['restore']['path']).exists())
                            self.assertEqual(sample.cloud(row).read_bytes(), sample.original)
                        else:
                            self.assertEqual(row['state'], 'failed')
                        with self.assertRaises(ProcessLookupError): os.kill(child, 0)
                    finally:
                        if process.poll() is None:
                            process.kill(); process.communicate()
                finally:
                    sample.doCleanups()

    def test_torrent_interruption_before_copy_has_distinct_interrupted_result(self):
        import io
        import test_archive_torrent as torrent_fixture
        output = io.StringIO()
        with patch.object(torrent_fixture.tool, 'run', side_effect=KeyboardInterrupt), patch('sys.stdout', output):
            self.assertEqual(torrent_fixture.tool.main(), 2)
        self.assertEqual(json.loads(output.getvalue())['error']['code'], 'INTERRUPTED')

    def test_real_user_unit_cancel_after_client_exit_kills_only_isolated_transport(self):
        check = subprocess.run(['loginctl', 'show-user', str(os.getuid()), '-p', 'Linger', '--value'], capture_output=True, text=True)
        if check.stdout.strip() != 'yes': self.skipTest('User linger unavailable')
        bridge = self.root / 'myct-cloud'
        source = fixture.SOURCE.read_text().replace("ROOT = Path.home() / '.local/share/myct/cloud-jobs'", 'ROOT = Path(' + repr(str(cloud.ROOT)) + ')')
        bridge.write_text(source)
        (self.root / 'myct_cloud_progress.py').write_text((fixture.SOURCE.parent / 'myct_cloud_progress.py').read_text())
        engine = self.root / 'myct-archive-torrent'
        original = self.root / 'original'; original.write_bytes(b'preserve original')
        previous = self.root / 'verified-backup'; previous.write_bytes(b'preserve backup')
        transport_pid = self.root / 'transport-pid'
        engine.write_text('import json,signal,subprocess,sys,os\nfrom pathlib import Path\n'
            'def interrupted(*_):\n raise KeyboardInterrupt()\n'
            'signal.signal(signal.SIGTERM,interrupted)\n'
            'child=subprocess.Popen([sys.executable,"-c","import time; time.sleep(30)"])\n'
            'Path(' + repr(str(transport_pid)) + ').write_text(str(child.pid))\n'
            'try:\n child.wait()\n print(json.dumps({"ok":True}))\n'
            'except KeyboardInterrupt:\n child.wait(timeout=5)\n print(json.dumps({"ok":False,"error":{"code":"INTERRUPTED"}}))\n sys.exit(2)\n')
        payload = base64.urlsafe_b64encode(json.dumps(self.fixture.work('archive')).encode()).decode().rstrip('=')
        try:
            accepted = subprocess.run([sys.executable, str(bridge), 'start', JOB, payload], capture_output=True, text=True, timeout=10)
            self.assertTrue(json.loads(accepted.stdout)['ok'], accepted.stdout)
            deadline = time.monotonic() + 5
            while not transport_pid.exists() and time.monotonic() < deadline: time.sleep(.02)
            self.assertTrue(transport_pid.exists())
            child = int(transport_pid.read_text())
            # A separate delivery process issues the explicit cancellation after the start client exited.
            cancel = subprocess.run([sys.executable, str(bridge), 'cancel', JOB], capture_output=True, text=True, timeout=10)
            self.assertTrue(json.loads(cancel.stdout)['ok'], cancel.stdout)
            self.assertTrue(json.loads(cancel.stdout)['task']['cancel_requested'])
            deadline = time.monotonic() + 7
            while time.monotonic() < deadline:
                row = cloud.run(['task', JOB])['task']
                if row['state'] not in ('starting', 'running', 'cancelling'): break
                time.sleep(.03)
            self.assertEqual(row['state'], 'cancelled', row)
            with self.assertRaises(ProcessLookupError): os.kill(child, 0)
            self.assertEqual(original.read_bytes(), b'preserve original'); self.assertEqual(previous.read_bytes(), b'preserve backup')
            self.assertEqual(cloud.cancel(JOB)['task']['state'], 'cancelled')
        finally:
            subprocess.run(['systemctl', '--user', 'stop', cloud.unit(JOB)], capture_output=True)
