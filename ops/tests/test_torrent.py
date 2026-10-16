import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import http.server
import threading

loader = importlib.machinery.SourceFileLoader('torrent', str(Path(__file__).parents[1] / 'myct-torrent'))
spec = importlib.util.spec_from_loader(loader.name, loader)
torrent = importlib.util.module_from_spec(spec)
loader.exec_module(torrent)
HASH = 'a' * 40
MAGNET = 'magnet:?xt=urn:btih:' + HASH

class FakeRpc:
    def __init__(self, root):
        self.root = root
        self.calls = []
    def call(self, method, params):
        self.calls.append((method, params))
        if method == 'torrent_get':
            return {'torrents': [{'hash_string': HASH, 'download_dir': str(self.root / HASH) if isinstance(self.root, Path) else self.root}]}
        if method == 'torrent_add':
            return {'torrent_duplicate': {'id': 1, 'hash_string': HASH}}
        return {}

class TorrentTest(unittest.TestCase):
    def test_validation_and_duplicate(self):
        self.assertEqual(MAGNET, torrent.magnet(MAGNET))
        for value in ['https://example.com', MAGNET + '\n', MAGNET + '&xs=http://127.0.0.1/a',
                      MAGNET + '&tr=http://127.0.0.1/a', 'magnet:?xt=urn:btih:nope', MAGNET + '&xt=urn:btih:' + HASH]:
            with self.assertRaises((torrent.Failure, ValueError)):
                torrent.magnet(value)
        with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
            rpc = FakeRpc(torrent.destination())
            self.assertTrue(torrent.run(['add', MAGNET], rpc)['duplicate'])
            self.assertEqual(MAGNET, rpc.calls[0][1]['filename'])
            self.assertFalse(rpc.calls[0][1]['paused'])

    def test_controls_exact_hash_and_preserve_data(self):
        rpc = FakeRpc(torrent.destination())
        for verb, method in [('pause', 'torrent_stop'), ('resume', 'torrent_start'), ('remove', 'torrent_remove')]:
            self.assertTrue(torrent.run([verb, HASH], rpc)['ok'])
            self.assertEqual(method, rpc.calls[-1][0])
            self.assertEqual([HASH], rpc.calls[-1][1]['ids'])
        self.assertIs(False, rpc.calls[-1][1]['delete_local_data'])
        for value in ['all', '1', '', HASH + '; touch /tmp/pwn']:
            with self.assertRaises(torrent.Failure):
                torrent.run(['remove', value], rpc)
        with self.assertRaises(torrent.Failure):
            torrent.run(['remove', HASH], FakeRpc('/tmp/other-owner'))

    def test_symlink_escape_rejected(self):
        with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
            (Path(home) / 'Downloads').symlink_to('/tmp')
            with self.assertRaises(torrent.Failure):
                torrent.destination()

    def test_real_http_csrf_handshake_and_no_ambiguous_retry(self):
        requests = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(request)
                if not self.headers.get('X-Transmission-Session-Id'):
                    self.send_response(409); self.send_header('X-Transmission-Session-Id', 'abc123'); self.end_headers()
                else:
                    self.send_response(200); self.end_headers()
                    self.wfile.write(json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': {'torrents': []}}).encode())
            def log_message(self, *args): pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            with patch.object(torrent, 'ENDPOINT_PORT', server.server_port):
                self.assertEqual({'torrents': []}, torrent.Rpc().call('torrent_get', {'fields': []}))
            self.assertEqual(2, len(requests))
            self.assertEqual(requests[0], requests[1])
        finally:
            server.shutdown(); server.server_close(); thread.join()
        with patch.object(torrent, 'ENDPOINT_PORT', server.server_port):
            with self.assertRaises(OSError):
                torrent.Rpc().call('torrent_get', {})

if __name__ == '__main__': unittest.main()
