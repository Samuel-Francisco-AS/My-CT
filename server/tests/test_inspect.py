import hashlib
import json
import os
from pathlib import Path
import pwd
import subprocess
import tempfile
import unittest

CLI = Path(__file__).resolve().parents[1] / 'myct-inspect'
HOME = Path(pwd.getpwuid(os.getuid()).pw_dir)

class InspectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='myct-inspect-test-', dir=HOME)
        self.root = Path(self.temp.name)
        self.file = self.root / "espaços ação ' $(id); [*]?.txt"
        self.file.write_bytes(b'abc')
    def tearDown(self):
        self.temp.cleanup()
    def run_cli(self, path, *args, **kwargs):
        return subprocess.run([str(CLI), str(path).encode().hex(), *args], capture_output=True, **kwargs)
    def test_known_sha_and_content_not_extension(self):
        result = self.run_cli(self.file)
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data['bytes'], 3)
        self.assertEqual(data['sha256'], hashlib.sha256(b'abc').hexdigest())
        self.assertEqual(data['path'], str(self.file))
        self.assertEqual(data['type'], 'text/plain')
        self.file.rename(self.root / 'fake.png')
        data = json.loads(self.run_cli(self.root / 'fake.png').stdout)
        self.assertEqual(data['extension'], 'png')
        self.assertEqual(data['type'], 'text/plain')
    def test_missing_and_unreadable(self):
        self.assertIn('inexistente', self.run_cli(self.root / 'missing').stderr.decode())
        self.file.chmod(0)
        try:
            result = self.run_cli(self.file)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('permissão', result.stderr.decode())
        finally:
            self.file.chmod(0o600)
    def test_symlinks_escape_and_special_files(self):
        link = self.root / 'link'
        link.symlink_to(self.file)
        directory = self.root / 'directory-link'
        directory.symlink_to(self.root, target_is_directory=True)
        fifo = self.root / 'fifo'
        os.mkfifo(fifo)
        for path in (link, directory / self.file.name, '/etc/passwd',
                     self.root / '..' / self.root.name / self.file.name, self.root, fifo):
            with self.subTest(path=path):
                result = self.run_cli(path, timeout=3)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, b'')
    def test_empty_and_binary(self):
        self.file.write_bytes(b'')
        self.assertEqual(json.loads(self.run_cli(self.file).stdout)['sha256'], hashlib.sha256(b'').hexdigest())
        self.file.write_bytes(b'\x00' * 512)
        self.assertIn('não identificado', json.loads(self.run_cli(self.file).stdout)['warning'])
    def test_closed_client_stops_inspection(self):
        result = self.run_cli(self.file, '--watch-stdin', input=b'')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('cancelou', result.stderr.decode())
    def test_cancellation_during_hash(self):
        large = self.root / 'large'
        with large.open('wb') as f:
            f.truncate(2 * 1024**3)
        process = subprocess.Popen([str(CLI), str(large).encode().hex(), '--watch-stdin'],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        import time
        time.sleep(.1)
        process.stdin.close()
        process.wait(timeout=3)
        self.assertNotEqual(process.returncode, 0)
        self.assertIn(b'cancelou', process.stderr.read())
        process.stdout.close(); process.stderr.close()
    def test_file_changed_while_hashing(self):
        large = self.root / 'changing'
        with large.open('wb') as f:
            f.truncate(256 * 1024**2)
        process = subprocess.Popen([str(CLI), str(large).encode().hex()], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        import time
        while process.poll() is None:
            os.utime(large, None)
            time.sleep(.005)
        out, error = process.communicate()
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(out, b'')
        self.assertIn(b'mudou', error)
