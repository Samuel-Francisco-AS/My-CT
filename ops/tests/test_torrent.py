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
            good_only = 'magnet:?' + urlencode([('xt', 'urn:btih:' + HASH), ('tr', live)])
            self.assertEqual(good_only, torrent.magnet(good_only))

            with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
                rpc = FakeRpc(torrent.destination())
                self.assertTrue(torrent.run(['add', raw], rpc)['duplicate'])
                self.assertEqual(cleaned, rpc.calls[0][1]['filename'])
                self.assertEqual(1, len([m for m, _ in rpc.calls if m == 'torrent_add']))

            # A trackerless magnet can still be handed to the daemon (DHT).
            all_dead = 'magnet:?' + urlencode([
                ('xt', 'urn:btih:' + HASH), ('tr', dead)])
            no_trackers = parse_qs(urlsplit(torrent.magnet(all_dead)).query)
            self.assertEqual(['urn:btih:' + HASH], no_trackers['xt'])
            self.assertNotIn('tr', no_trackers)

    def test_empty_magnet_separators_are_ignored_but_fields_stay_strict(self):
        # Link generators may emit 63 segments with 31 empty entries:
        # repeated '&&' separators must not invalidate otherwise valid magnets.
        good = 'udp://good.example:6969/announce'
        offline = 'udp://offline.example:6969/announce'
        encoded_good = 'udp%3A%2F%2Fgood.example%3A6969%2Fannounce'
        encoded_offline = 'udp%3A%2F%2Foffline.example%3A6969%2Fannounce'
        fields = [
            'xt=urn:btih:' + HASH,
            'dn=Nome%20original',
            'tr=' + encoded_good,
            'tr=' + encoded_offline,
        ]
        malformed_separators = 'magnet:?' + '&&'.join(fields) + '&&'
        expected = 'magnet:?' + '&'.join(fields[:3])

        def dns(host, port, type):
            if host == 'offline.example':
                raise socket.gaierror(socket.EAI_NONAME, 'not found')
            return [(socket.AF_INET, socket.SOCK_DGRAM, 17, '', ('8.8.8.8', port))]

        with patch.object(torrent.socket, 'getaddrinfo', side_effect=dns):
            cleaned = torrent.magnet(malformed_separators)
            self.assertEqual(expected, cleaned)
            self.assertIn('xt=urn:btih:', cleaned)
            self.assertNotIn('xt=urn%3A', cleaned)
            self.assertEqual('magnet:?' + '&'.join(fields[:3]),
                             torrent.magnet('magnet:?' + '&&'.join(fields[:3]) + '&'))
            with tempfile.TemporaryDirectory() as home, patch.object(
                    torrent.Path, 'home', return_value=Path(home)):
                rpc = FakeRpc(torrent.destination())
                self.assertTrue(torrent.run(['add', malformed_separators], rpc)['duplicate'])
                self.assertEqual(cleaned, rpc.calls[0][1]['filename'])

        with self.assertRaises(ValueError):
            torrent.magnet(MAGNET + '&&bare-parameter')
        with self.assertRaises(torrent.Failure):
            torrent.magnet(MAGNET + '&&xs=http://example.org/metadata')
        self.assertEqual(MAGNET, torrent.magnet('magnet:?&&xt=urn:btih:' + HASH + '&&'))

    def test_filtered_magnet_keeps_original_xt_encoding_for_transmission_413(self):
        # libtransmission 4.1.3 matches raw xt values beginning with 'urn:btih:'.
        # Rebuilding query params with urlencode() used to convert ':' to '%3A'
        # and cause JSON-RPC error 4 ('unrecognized info').
        good = 'udp://good.example:6969/announce'
        bad = 'udp://offline.example:6969/announce'
        good_param = 'udp%3A%2F%2Fgood.example%3A6969%2Fannounce'
        bad_param = 'udp%3A%2F%2Foffline.example%3A6969%2Fannounce'
        magnet_raw = (
            MAGNET + '&dn=Nome%20do%20Arquivo' +
            '&tr=' + good_param + '&tr=' + bad_param
        )
        expected = MAGNET + '&dn=Nome%20do%20Arquivo&tr=' + good_param
        def dns(host, port, type):
            if host == 'offline.example':
                raise socket.gaierror(socket.EAI_NONAME, 'not found')
            return [(socket.AF_INET, socket.SOCK_DGRAM, 17, '', ('8.8.8.8', port))]

        with patch.object(torrent.socket, 'getaddrinfo', side_effect=dns):
            cleaned = torrent.magnet(magnet_raw)
            self.assertEqual(expected, cleaned)
            self.assertIn('xt=urn:btih:', cleaned)
            self.assertNotIn('xt=urn%3A', cleaned)
            with tempfile.TemporaryDirectory() as home, patch.object(
                    torrent.Path, 'home', return_value=Path(home)):
                rpc = FakeRpc(torrent.destination())
                self.assertTrue(torrent.run(['add', magnet_raw], rpc)['duplicate'])
                self.assertEqual(expected, rpc.calls[0][1]['filename'])

    def test_nonpublic_dns_answer_is_filtered_without_forwarding_to_rpc(self):
        good = 'udp://public.example:6969/announce'
        private = 'udp://parked.example:6969/announce'
        raw = 'magnet:?' + urlencode([
            ('xt', 'urn:btih:' + HASH), ('tr', good), ('tr', private)])
        public_ip = (socket.AF_INET, socket.SOCK_DGRAM, 17, '', ('8.8.8.8', 6969))
        private_ip = (socket.AF_INET, socket.SOCK_DGRAM, 17, '', ('192.168.0.1', 6969))

        def dns(host, port, type):
            return [private_ip] if host == 'parked.example' else [public_ip]

        with patch.object(torrent.socket, 'getaddrinfo', side_effect=dns):
            cleaned = torrent.magnet(raw)
            self.assertEqual([good], parse_qs(urlsplit(cleaned).query)['tr'])
            with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path, 'home', return_value=Path(home)):
                rpc = FakeRpc(torrent.destination())
                self.assertTrue(torrent.run(['add', raw], rpc)['duplicate'])
                self.assertEqual(cleaned, rpc.calls[0][1]['filename'])
            # All unavailable after DNS resolution: the remaining magnet still carries xt.
            only_private = MAGNET + '&tr=' + urlencode({'tr': private})[3:]
            self.assertNotIn('tr', parse_qs(urlsplit(torrent.magnet(only_private)).query))
            # A mixed public/private answer for the same hostname is also discarded.
            with patch.object(torrent.socket, 'getaddrinfo', return_value=[public_ip, private_ip]):
                self.assertNotIn('tr', parse_qs(urlsplit(torrent.magnet(only_private)).query))

    def test_private_literal_trackers_and_non_dns_resolver_errors_stay_rejected(self):
        for hostname in ('127.0.0.1', '192.168.0.1', '[::1]', '169.254.0.1'):
            raw = MAGNET + '&tr=' + urlencode({'tr': 'udp://' + hostname + ':6969/announce'})[3:]
            with self.assertRaisesRegex(torrent.Failure, 'endereço público'):
                torrent.magnet(raw)
        tracker = 'udp://public.example:6969/announce'
        raw = MAGNET + '&tr=' + urlencode({'tr': tracker})[3:]
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

    def test_rpc_engine_error_exposes_code_without_leaking_magnet(self):
        requests = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                if not self.headers.get('X-Transmission-Session-Id'):
                    self.send_response(409)
                    self.send_header('X-Transmission-Session-Id', 'abc123')
                    self.end_headers()
                    return
                self.send_response(200)
                self.end_headers()
                response = {'jsonrpc': '2.0', 'id': 1,
                            'error': {'code': 11, 'message': 'unrecognized info'}}
                self.wfile.write(json.dumps(response).encode())
            def log_message(self, *args): pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with patch.object(torrent, 'ENDPOINT_PORT', server.server_port):
                with self.assertRaisesRegex(torrent.Failure,
                    r'Transmission recusou torrent_add: unrecognized info \(código 11\)') as caught:
                    torrent.Rpc().call('torrent_add', {'filename': MAGNET})
            self.assertNotIn(MAGNET, str(caught.exception))
            self.assertEqual(2, len(requests))  # CSRF 409 only; never retry an RPC error.
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

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
