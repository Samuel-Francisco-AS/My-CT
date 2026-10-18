"""Real curl + transient user systemd integration; no Core/DB changes.
Loopback HTTP fixture, tiny payloads, isolated HOME. No SSH acceptance implied.
"""
import hashlib
import http.server
import os
import resource
import uuid
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest

BIN = Path(__file__).resolve().parents[1] / 'myct-download'
PAYLOAD = bytes(range(256)) * 256
SLOW_DELAY = .06


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == '/missing':
            self.send_error(404)
            return
        if self.path in ('/redirect', '/unsafe'):
            self.send_response(302)
            self.send_header('Location', '/file' if self.path == '/redirect' else 'file:///etc/passwd')
            self.end_headers()
            return
        self.send_response(200)
        if self.path != '/unknown':
            self.send_header('Content-Length', str(2**60 if self.path == '/huge' else len(PAYLOAD)))
        self.end_headers()
        try:
            if self.path == '/stall':
                time.sleep(36)
            elif self.path == '/broken':
                self.wfile.write(PAYLOAD[:1024])
            elif self.path == '/slow':
                for i in range(0, len(PAYLOAD), 1024):
                    self.wfile.write(PAYLOAD[i:i+1024])
                    self.wfile.flush()
                    time.sleep(SLOW_DELAY)
            else:
                self.wfile.write(PAYLOAD)
        except (BrokenPipeError, ConnectionResetError):
            pass


@unittest.skipUnless(BIN.exists(), 'build myct-download first')
class Downloads(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.http.daemon_threads = True
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f'http://127.0.0.1:{cls.http.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()
        cls.thread.join()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='myct-download-test-')
        self.home = Path(self.tmp.name)
        self.env = dict(os.environ, HOME=str(self.home))
        self.ids = []

    def tearDown(self):
        for ident in self.ids:
            self.cli('cancel', ident)
        self.tmp.cleanup()

    def cli(self, *args):
        return subprocess.run([str(BIN), *args], env=self.env, text=True, capture_output=True, timeout=20)

    def start(self, path, name='sample.bin'):
        r = self.cli('start', self.base + path, name, '--allow-private')
        self.assertEqual(r.returncode, 0, r.stderr)
        ident = r.stdout.strip()
        self.ids.append(ident)
        return ident

    def status(self, ident):
        r = self.cli('status', ident)
        self.assertEqual(r.returncode, 0, r.stderr)
        return dict(s.split(': ', 1) for s in r.stdout.splitlines())

    def wait(self, ident, timeout=10):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            s = self.status(ident)
            if s['state'] not in ('running', 'starting'):
                return s
            time.sleep(.1)
        self.fail(f'download did not finish: {s}')

    def test_completed_redirect_unknown_and_no_overwrite(self):
        for endpoint in ('/file', '/redirect', '/unknown'):
            ident = self.start(endpoint)
            s = self.wait(ident)
            self.assertEqual(s['state'], 'completed', s)
            self.assertEqual(hashlib.sha256(Path(s['path']).read_bytes()).digest(), hashlib.sha256(PAYLOAD).digest())
            self.assertFalse(Path(s['path'] + '.part').exists())
            self.assertEqual(s['total'], '-1' if endpoint == '/unknown' else str(len(PAYLOAD)))
        r = self.cli('list')
        self.assertEqual(r.stdout.count('state: completed'), 3)

    def test_progress_cancel_and_busy(self):
        ident = self.start('/slow')
        time.sleep(.5)
        s = self.status(ident)
        self.assertEqual(s['state'], 'running', s)
        self.assertGreater(int(s['bytes']), 0)
        self.assertEqual(int(s['total']), len(PAYLOAD))
        busy = self.start('/file')
        self.assertEqual(self.wait(busy)['state'], 'failed')
        r = self.cli('cancel', ident)
        self.assertEqual(r.returncode, 0, r.stderr)
        s = self.status(ident)
        self.assertEqual(s['state'], 'canceled', s)
        self.assertTrue(s['path'].endswith('.part'))
        self.assertFalse(Path(s['path'][:-5]).exists())
        before = Path(s['path']).stat().st_size
        time.sleep(.15)
        self.assertEqual(before, Path(s['path']).stat().st_size)

    def test_http_network_and_protocol_failures(self):
        for endpoint in ('/missing', '/broken', '/unsafe', '/huge'):
            s = self.wait(self.start(endpoint))
            self.assertEqual(s['state'], 'failed', s)
            self.assertTrue(s['path'].endswith('.part'))
            self.assertFalse(Path(s['path'][:-5]).exists())

    def test_timeout(self):
        s = self.wait(self.start('/stall'), timeout=38)
        self.assertEqual(s['state'], 'failed', s)
        self.assertIn('Timeout', s['message'])

    def test_validation(self):
        for url in ('file:///etc/passwd', 'http://user:pass@example.com/', self.base, 'http://169.254.169.254/', 'http://[::1]/'):
            self.assertNotEqual(self.cli('start', url).returncode, 0, url)
        for name in ('../escape', '/tmp/escape', 'x.part', '-option', 'a\nname'):
            self.assertNotEqual(self.cli('start', self.base, name, '--allow-private').returncode, 0, name)
        self.assertNotEqual(self.cli('status', '../escape').returncode, 0)
        self.assertNotEqual(self.cli('cancel', 'a' * 36).returncode, 0)

    def test_existing_final_and_interrupted(self):
        ident = self.start('/slow')
        time.sleep(.2)
        s = self.status(ident)
        dest = Path(s['path'][:-5])
        dest.write_bytes(b'keep me')
        s = self.wait(ident)
        self.assertEqual(s['state'], 'failed', s)
        self.assertEqual(dest.read_bytes(), b'keep me')
        p = self.home / '.local/share/myct/downloads' / ident
        (p / 'state').write_text('running\n')  # crash/reboot metadata recovery, simulated
        self.assertEqual(self.status(ident)['state'], 'interrupted')

    def test_completion_during_active_probe_uses_final_state(self):
        # Deterministic probe race; no unit, HTTP request or persistent installation.
        ident = str(uuid.uuid4())
        p = self.home / '.local/share/myct/downloads' / ident
        p.mkdir(parents=True)
        (p / 'state').write_text('running\n')
        (p / 'name').write_text('probe.bin\n')
        commands = self.home / 'probe-bin'
        commands.mkdir()
        probe = commands / 'systemctl'
        probe.write_text('#!/bin/sh\nprintf "completed\\n" > "$HOME/.local/share/myct/downloads/' + ident + '/state"\nexit 3\n')
        probe.chmod(0o700)
        env = dict(self.env, PATH=str(commands) + ':' + os.environ['PATH'])
        result = subprocess.run([str(BIN), 'status', ident], env=env,
                                text=True, capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('state: completed\n', result.stdout)

    def test_delete_completed_and_canceled_downloads_removes_only_owned_payload(self):
        for state in ('completed', 'canceled'):
            ident = str(uuid.uuid4())
            job = self.home / '.local/share/myct/downloads' / ident
            job.mkdir(parents=True)
            (job / 'name').write_text('demo.bin\\n')
            (job / 'state').write_text(state + '\\n')
            folder = self.home / 'Downloads/My-CT' / ident
            folder.mkdir(parents=True)
            payload = folder / ('demo.bin' if state == 'completed' else 'demo.bin.part')
            payload.write_bytes(b'owned')
            unrelated = folder / 'user-notes.txt'
            unrelated.write_bytes(b'keep')
            r = self.cli('delete', ident)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertFalse(payload.exists())
            self.assertFalse(job.exists())
            self.assertEqual(unrelated.read_bytes(), b'keep')
        self.assertNotEqual(self.cli('delete', '../outside').returncode, 0)

    def test_delete_rejects_active_and_externally_linked_or_symlinked_payloads(self):
        ident = str(uuid.uuid4())
        job = self.home / '.local/share/myct/downloads' / ident
        job.mkdir(parents=True)
        (job / 'name').write_text('demo.bin\\n')
        (job / 'state').write_text('running\\n')
        folder = self.home / 'Downloads/My-CT' / ident
        folder.mkdir(parents=True)
        payload = folder / 'demo.bin.part'
        payload.write_bytes(b'partial')
        self.assertNotEqual(self.cli('delete', ident).returncode, 0)
        self.assertTrue(payload.exists())
        (job / 'state').write_text('canceled\\n')
        linked = self.home / 'outside.bin'
        os.link(payload, linked)
        self.assertNotEqual(self.cli('delete', ident).returncode, 0)
        self.assertTrue(linked.exists())
        linked.unlink()
        payload.unlink()
        payload.symlink_to('/etc/passwd')
        self.assertNotEqual(self.cli('delete', ident).returncode, 0)
        self.assertTrue(job.exists())

    def test_write_limit(self):
        # Real kernel file-size limit: simulates storage write failure without filling disk.
        ident = str(uuid.uuid4())
        p = self.home / '.local/share/myct/downloads' / ident
        p.mkdir(parents=True)
        for name, value in {'url': self.base + '/file', 'name': 'limit.bin', 'private': 'yes', 'state': 'starting'}.items():
            (p / name).write_text(value + '\n')
        def limit():
            resource.setrlimit(resource.RLIMIT_FSIZE, (4096, 4096))
        r = subprocess.run([str(BIN), '--worker', ident], env=self.env,
                           capture_output=True, timeout=10, preexec_fn=limit)
        self.assertNotEqual(r.returncode, 0)
        s = self.status(ident)
        self.assertEqual(s['state'], 'failed', s)
        self.assertIn('Falha ao gravar', s['message'])
        self.assertTrue(s['path'].endswith('.part'))
        self.assertFalse(Path(s['path'][:-5]).exists())


if __name__ == '__main__':
    import sys
    if sys.argv[1:] == ['--serve-ssh-fixture']:
        PAYLOAD = bytes(range(256)) * 1024
        SLOW_DELAY = .25  # 256 KiB over ~64 s; loopback only.
        print('127.0.0.1:18769/slow SHA256=' + hashlib.sha256(PAYLOAD).hexdigest(), flush=True)
        http.server.ThreadingHTTPServer(('127.0.0.1', 18769), Handler).serve_forever()
    else:
        unittest.main()
