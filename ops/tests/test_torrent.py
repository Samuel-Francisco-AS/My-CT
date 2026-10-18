import importlib.machinery
import importlib.util
import json
import socket
from urllib.parse import parse_qs, urlencode, urlsplit
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
    def test_accumulated_stats_are_read_only_and_forwarded_per_torrent(self):
        with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
            root = torrent.destination()
            class Engine(FakeRpc):
                def call(inner, method, params):
                    self.assertEqual('torrent_get', method)
                    for key in ('downloaded_ever', 'uploaded_ever', 'upload_ratio'):
                        self.assertIn(key, params['fields'])
                    inner.calls.append((method, params))
                    return {'torrents': [{'hash_string': HASH, 'download_dir': str(root / HASH),
                        'downloaded_ever': 1234, 'uploaded_ever': 9876, 'upload_ratio': 8.0}]}
            rpc = Engine(root)
            row = torrent.records(rpc)[0]
            self.assertEqual(1234, row['downloaded_ever'])
            self.assertEqual(9876, row['uploaded_ever'])
            self.assertEqual(8.0, row['upload_ratio'])
            self.assertEqual(1, len(rpc.calls))

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
            self.assertTrue(rpc.calls[0][1]['paused'])
            self.assertFalse(any(method == 'torrent_start' for method, _ in rpc.calls))

    def test_unresolvable_trackers_are_dropped_before_rpc(self):
        live = 'udp://live.example:6969/announce'
        dead = 'udp://dead.example:6969/announce'
        raw = 'magnet:?' + urlencode([
            ('xt', 'urn:btih:' + HASH), ('dn', 'Arquivo de teste'),
            ('tr', live), ('tr', dead)])
        def dns(host, port, type):
            if host == 'dead.example':
                raise socket.gaierror(socket.EAI_NONAME, 'Unknown host')
            return [(socket.AF_INET, socket.SOCK_DGRAM, 17, '', ('8.8.8.8', port))]

        with patch.object(torrent.socket, 'getaddrinfo', side_effect=dns):
            cleaned = torrent.magnet(raw)
            params = parse_qs(urlsplit(cleaned).query)
            self.assertEqual(['urn:btih:' + HASH], params['xt'])
            self.assertEqual(['Arquivo de teste'], params['dn'])
            self.assertEqual([live], params['tr'])
            # No needless rewriting of the original URI when DNS works.
            self.assertEqual(MAGNET + '&tr=' + urlencode({'tr': live})[3:],
                             torrent.magnet(MAGNET + '&tr=' + urlencode({'tr': live})[3:]))

            with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
                rpc = FakeRpc(torrent.destination())
                self.assertTrue(torrent.run(['add', raw], rpc)['duplicate'])
                self.assertEqual(cleaned, rpc.calls[0][1]['filename'])
                self.assertEqual(1, len([m for m, _ in rpc.calls if m == 'torrent_add']))

            # A trackerless magnet can still be handed to the daemon (DHT).
            all_dead = 'magnet:?' + urlencode([
                ('xt', 'urn:btih:' + HASH), ('tr', dead)])
            self.assertEqual(['urn:btih:' + HASH],
                             parse_qs(urlsplit(torrent.magnet(all_dead)).query)['xt'])
            self.assertNotIn('tr', parse_qs(urlsplit(torrent.magnet(all_dead)).query))

    def test_private_dns_answers_and_other_resolver_errors_stay_rejected(self):
        tracker = 'udp://private.example:6969/announce'
        raw = 'magnet:?' + urlencode([('xt', 'urn:btih:' + HASH), ('tr', tracker)])
        answer = [(socket.AF_INET, socket.SOCK_DGRAM, 17, '', ('192.168.0.1', 6969))]
        with patch.object(torrent.socket, 'getaddrinfo', return_value=answer):
            with self.assertRaisesRegex(torrent.Failure, 'endereço público'):
                torrent.magnet(raw)
        with patch.object(torrent.socket, 'getaddrinfo', side_effect=OSError('resolver I/O error')):
            with self.assertRaisesRegex(torrent.Failure, 'Falha de rede'):
                torrent.magnet(raw)

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

    def test_only_new_directories_register_before_start(self):
        for existing in (False, True):
            with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
                root = torrent.destination()
                if existing: (root / HASH).mkdir(parents=True)
                class Engine(FakeRpc):
                    def call(inner, method, params):
                        if method == 'torrent_add':
                            self.assertTrue(params['paused'])
                            return {'torrent_added': {'id':1, 'hash_string':HASH}}
                        self.assertEqual('torrent_start', method)
                        self.assertEqual(not existing, torrent.read_naming(HASH) is not None)
                        return {}
                torrent.run(['add', MAGNET], Engine(root))
                self.assertEqual(not existing, torrent.read_naming(HASH) is not None)

    def test_real_title_once_and_no_legacy_migration(self):
        with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
            root = torrent.destination()
            (root / HASH).mkdir(parents=True)
            class Engine(FakeRpc):
                def __init__(self):
                    super().__init__(root)
                    self.directory = str(root / HASH)
                    self.ready = False
                def call(self, method, params):
                    self.calls.append((method, params))
                    if method == 'torrent_get':
                        return {'torrents': [{'hash_string': HASH, 'download_dir': self.directory,
                            'name': '../Nome real ação/CON', 'metadata_percent_complete': int(self.ready),
                            'left_until_done': 0, 'error': 0}]}
                    if method == 'torrent_set_location':
                        self.directory = params['location']
                    return {}
            rpc = Engine()
            self.assertEqual('legacy-untouched', torrent.organize(rpc, HASH)['naming'])
            with torrent.naming_lock(HASH):
                torrent.write_naming(HASH, {'state': 'pending', 'target': None})
            self.assertEqual('waiting-for-completion', torrent.organize(rpc, HASH)['naming'])
            rpc.ready = True
            self.assertEqual('done', torrent.organize(rpc, HASH)['naming'])
            self.assertTrue(rpc.directory.endswith('Nome-real-ação-CON-aaaaaaaa'))
            torrent.organize(rpc, HASH)
            mutations = [(m, p) for m,p in rpc.calls if m != 'torrent_get']
            self.assertEqual([('torrent_set_location', {'ids':[HASH], 'location':rpc.directory, 'move':True})], mutations)
            self.assertEqual(1, len(torrent.records(rpc)))
            rpc.directory = str(root / 'unregistered-aaaaaaaa')
            self.assertEqual([], torrent.records(rpc))

    def test_names_collisions_symlinks_and_no_retry_after_ambiguous_move(self):
        with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
            root = torrent.destination(); (root / HASH).mkdir(parents=True)
            for title in ['../a/../../b', 'CON', 'NUL.txt', '\x00\n\u202e..', '中文-ação' * 100]:
                name = torrent.readable_name(title, HASH)
                self.assertTrue(torrent.safe_named_component(name, HASH))
                self.assertFalse(name.startswith('.')); self.assertNotIn('/', name)
            self.assertNotEqual(torrent.readable_name('same', HASH), torrent.readable_name('same', 'b'*40))
            class Engine(FakeRpc):
                def call(self, method, params):
                    self.calls.append((method, params))
                    if method == 'torrent_get':
                        return {'torrents':[{'hash_string':HASH,'download_dir':str(root/HASH),
                            'metadata_percent_complete':1,'left_until_done':0,'name':'test','error':0}]}
                    raise OSError('Ambiguous RPC result')
            rpc = Engine(root)
            with torrent.naming_lock(HASH):
                torrent.write_naming(HASH, {'state':'pending','target':None})
            target = root / torrent.readable_name('test', HASH)
            target.symlink_to('/tmp')
            self.assertEqual('blocked-destination-exists', torrent.organize(rpc, HASH)['naming'])
            target.unlink()  # Only our test-owned symlink inside TemporaryDirectory.
            with torrent.naming_lock(HASH):
                torrent.write_naming(HASH, {'state':'pending','target':None})
            with self.assertRaises(OSError): torrent.organize(rpc, HASH)
            self.assertEqual('submitted', torrent.organize(rpc, HASH)['naming'])
            self.assertEqual(1, sum(m == 'torrent_set_location' for m, _ in rpc.calls))

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
