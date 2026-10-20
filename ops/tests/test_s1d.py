"""S1D fixtures: no real keys, trackers, downloads or Transmission mutations."""
import base64
import hashlib
import http.client
import http.server
import io
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import MagicMock, patch

from test_search import search, FakeApi, KEY, PROXY, row
from test_torrent import torrent
from test_archive_torrent import bencode

INFO = {b'name': b'fixture.txt', b'length': 3, b'piece length': 16384,
        b'pieces': hashlib.sha1(b'abc').digest()}
RAW = bencode({b'info': INFO})
HASH = hashlib.sha1(bencode(INFO)).hexdigest()
DNS = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('8.8.8.8', 443))]


class PagesAndIdentityTest(unittest.TestCase):
    def test_public_pages_and_sensitive_urls_fail_closed(self):
        with patch.object(search.socket, 'getaddrinfo', return_value=DNS):
            search.public_host.cache_clear()
            for url in ['https://example.org/view/123', 'https://example.org/viewtopic.php?t=123',
                        'https://example.org/torrent/123/ubuntu', 'https://example.org/ubuntu-torrent-123.html']:
                self.assertEqual(url, search.public_page(url, KEY))
            for url in [PROXY, 'file:///tmp/test', 'https://user:pass@example.org/view/1',
                        'https://example.org:9696/view/1', 'https://example.org/view/1?passkey=secret',
                        'https://example.org/api/view/1', 'https://example.org/download/1',
                        'https://example.org/view/' + KEY, 'https://example.org/view/1#secret',
                        'https://example.org/view/1?key=secret', 'https://example.org/opaque',
                        'https://example.org/view/1%0a', 'https://example.org/view/1?x=2',
                        'https://example.org/view/../1', 'https://example.local/view/1']:
                self.assertIsNone(search.public_page(url, KEY), url)
        for ip in ['127.0.0.1', '10.0.0.1', '169.254.169.254', '::1', '192.168.0.1']:
            search.public_host.cache_clear()
            with patch.object(search.socket, 'getaddrinfo', return_value=[(socket.AF_INET, 1, 6, '', (ip, 443))]):
                self.assertFalse(search.public_host('host.example.org'))
        search.public_host.cache_clear()
        with patch.object(search.socket, 'getaddrinfo', side_effect=socket.gaierror()):
            self.assertFalse(search.public_host('host.example.org'))

    def test_only_info_url_is_exported_and_proxy_is_strict(self):
        with patch.object(search, 'public_host', return_value=True):
            item = search.simplify(row(guid='stable', downloadUrl=PROXY, infoUrl='https://example.org/view/1'), KEY)
        self.assertTrue(item['torrent_available']); self.assertEqual(64, len(item['result_ref']))
        self.assertEqual('https://example.org/view/1', item['public_url'])
        self.assertNotIn(KEY, json.dumps(item)); self.assertNotIn('opaque', json.dumps(item))
        self.assertIsNone(search.simplify(row(guid='stable', downloadUrl='https://example.org/view/1'), KEY)['public_url'])
        for proxy in [PROXY.replace('/1/download', '/2/download'), PROXY.replace('127.0.0.1', '127.0.0.2'),
                      PROXY.replace('127.0.0.1', 'example.org'), PROXY.replace(KEY, 'a'*32),
                      PROXY + '&apikey=duplicate', PROXY + '#secret', PROXY.replace('http:', 'https:')]:
            self.assertIsNone(search.proxy_path(proxy, KEY, 1))
        self.assertNotIn(KEY, search.proxy_path(PROXY, KEY, 1))

    def test_identity_stable_with_new_proxy_but_not_changed_release(self):
        original = row(guid='stable', downloadUrl=PROXY)
        ref = search.reference(original)
        self.assertEqual(ref, search.reference({**original, 'downloadUrl': PROXY.replace('opaque', 'newcipher')}))
        for changes in [{'title':'Other'}, {'size':999}, {'guid':'changed'}, {'indexerId':2}]:
            self.assertNotEqual(ref, search.reference({**original, **changes}))
        self.assertIsNone(search.reference(row()))

    def test_resolve_requeries_one_enabled_source_and_does_not_add_on_search(self):
        original = row(guid='stable', downloadUrl=PROXY)
        api = FakeApi([original]); api.download = MagicMock(return_value=RAW)
        with patch.object(search, 'add_metainfo', return_value={'state':'added'}) as add:
            search.run(['search', 'Ubuntu'], api); add.assert_not_called(); api.download.assert_not_called()
            args = ['add-torrent', 'Ubuntu', '--indexer', '1', '--result', search.reference(original)]
            self.assertEqual('added', search.run(args, api)['state']); add.assert_called_once_with(RAW)
            self.assertIn('indexerIds=1', api.calls[-1][0])
            api.rows = [{**original, 'size':222}]
            with self.assertRaises(search.Failure) as err: search.run(args, api)
            self.assertEqual('stale_result', err.exception.code)
            api.sources = [{'id':1,'enable':False}]
            with self.assertRaises(search.Failure): search.run(args, api)
            self.assertEqual(1, add.call_count)
        for args in [['add-torrent','Ubuntu','--indexer','1','--result','https://evil.org'],
                     ['add-torrent','Ubuntu','--indexer','0','--result','a'*64]]:
            with self.assertRaises(search.Failure): search.validate_args(args)

    def test_sources_with_distinct_actions_are_not_hidden_by_dedup(self):
        a = row(guid='a', downloadUrl=PROXY)
        b = row(guid='b', indexerId=2, downloadUrl=PROXY.replace('/1/', '/2/'))
        api = FakeApi([a,b], sources=[{'id':i,'name':str(i),'enable':True,'protocol':'torrent'} for i in [1,2]])
        items = search.run(['search','Ubuntu'],api)['results']
        self.assertEqual(2,len(items)); self.assertNotEqual(items[0]['id'],items[1]['id'])


class ProxyBytesTest(unittest.TestCase):
    def test_real_http_client_binary_limits_and_no_redirect_follow(self):
        scenarios = [(200, 'application/x-bittorrent', RAW, None, None),
                     (200, 'text/html', b'<html>secret</html>', None, 'html_response'),
                     (200, 'application/octet-stream', b'<html>secret</html>', None, 'html_response'),
                     (200, 'application/x-bittorrent', b'not torrent', None, 'invalid_metainfo'),
                     (200, 'application/x-bittorrent', b'x', str(search.META_LIMIT+1), 'file_limit'),
                     (301, '', b'', None, 'download_redirect'), (302, '', b'', None, 'download_redirect'),
                     (401, '', b'', None, 'authentication'), (403, '', b'', None, 'authentication'),
                     (404, '', b'', None, 'expired_file'), (410, '', b'', None, 'expired_file'),
                     (500, '', b'', None, 'download_unavailable')]
        for status, ctype, payload, length, code in scenarios:
            class Handler(http.server.BaseHTTPRequestHandler):
                def do_GET(inner):
                    inner.send_response(status); inner.send_header('Content-Type',ctype)
                    inner.send_header('Location','http://169.254.169.254/secret')
                    inner.send_header('Content-Length', length or str(len(payload)))
                    inner.end_headers()
                    inner.wfile.write(payload)
                def log_message(*args): pass
            with http.server.HTTPServer(('127.0.0.1',0),Handler) as server:
                thread=threading.Thread(target=server.handle_request);thread.start()
                with patch.object(search,'PORT',server.server_port):
                    if code:
                        with self.assertRaises(search.Failure) as caught: search.Api(KEY).download('/1/download?link=fixture')
                        self.assertEqual(code,caught.exception.code)
                    else: self.assertEqual(RAW,search.Api(KEY).download('/1/download?link=fixture'))
                thread.join(2)

    def test_stream_size_and_timeout(self):
        for payload, code in [(b'd'*(search.META_LIMIT+1),'file_limit'), (b'', 'invalid_metainfo')]:
            conn=MagicMock(); response=conn.getresponse.return_value
            response.status=200; response.getheader.side_effect=lambda name, default=None: default
            response.read1.side_effect=[payload,b'']
            with patch.object(search.http.client,'HTTPConnection',return_value=conn):
                with self.assertRaises(search.Failure) as caught: search.Api(KEY).download('/fixed')
                self.assertEqual(code,caught.exception.code)
                conn.close.assert_called_once()
        conn=MagicMock();conn.request.side_effect=TimeoutError(KEY)
        with patch.object(search.http.client,'HTTPConnection',return_value=conn):
            with self.assertRaises(search.Failure) as caught: search.Api(KEY).download('/fixed')
            self.assertEqual('timeout',caught.exception.code); self.assertNotIn(KEY,str(caught.exception))

    def test_local_stdin_transfer_and_sanitized_helper_failures(self):
        result = subprocess.CompletedProcess([],0,json.dumps({'duplicate':True,'id':1,'hash':HASH}).encode(),b'')
        with patch.object(search.subprocess,'run',return_value=result) as run:
            self.assertTrue(search.add_metainfo(RAW)['duplicate'])
            args, kw=run.call_args; self.assertEqual('add-metainfo-stdin',args[0][-1]);self.assertEqual(RAW,kw['input'])
            self.assertNotIn(RAW.decode('latin1'),str(args));self.assertNotIn('shell',kw)
        for result in [subprocess.CompletedProcess([],2,b'',KEY.encode()),
                       subprocess.CompletedProcess([],0,KEY.encode(),b'')]:
            with patch.object(search.subprocess,'run',return_value=result):
                with self.assertRaises(search.Failure) as caught: search.add_metainfo(RAW)
                self.assertNotIn(KEY,str(caught.exception))
        with patch.object(search.subprocess,'run',side_effect=subprocess.TimeoutExpired('helper',25)):
            with self.assertRaises(search.Failure) as caught: search.add_metainfo(RAW)
            self.assertEqual('addition_uncertain',caught.exception.code)


class MetainfoTest(unittest.TestCase):
    def test_v1_hash_and_private_trackers_are_preserved_in_rpc(self):
        secret = b'https://tracker.example.org/announce?passkey=SYNTHETIC-SECRET'
        private_info = {**INFO,b'private':1}
        raw = bencode({b'announce':secret,b'announce-list':[[secret]],b'info':private_info})
        key = hashlib.sha1(bencode(private_info)).hexdigest()
        for duplicate in [False,True]:
            with tempfile.TemporaryDirectory() as home, patch.object(torrent.Path,'home',return_value=Path(home)), \
                 patch.object(torrent.socket,'getaddrinfo',return_value=DNS):
                rpc=MagicMock()
                rpc.call.side_effect=lambda method,params: ({'torrent_duplicate' if duplicate else 'torrent_added':
                    {'id':1,'hash_string':key}} if method=='torrent_add' else {})
                reply=torrent.run(['add-metainfo-stdin'],rpc,raw)
                self.assertEqual(duplicate,reply['duplicate']);self.assertEqual(key,reply['hash'])
                params=rpc.call.call_args_list[0].args[1]
                self.assertTrue(params['paused']);self.assertEqual(raw,base64.b64decode(params['metainfo']))
                self.assertTrue(params['download_dir'].endswith('/'+key));self.assertNotIn('filename',params)
                self.assertEqual(not duplicate,any(c.args[0]=='torrent_start' for c in rpc.call.call_args_list))
                if not duplicate:self.assertEqual('pending',torrent.read_naming(key)['state'])
                self.assertNotIn('SYNTHETIC-SECRET',json.dumps(reply))

    def test_invalid_unsupported_oversized_and_traversal_never_reach_rpc(self):
        invalid=[b'',b'<html/>',RAW+b'junk',b'd4:infoi1ee',b'd4:info'+bencode(INFO)+b'4:info'+bencode(INFO)+b'e',
                 b'x'*(torrent.MAX_META+1),b'li01ee']
        for changes in [{b'meta version':2},{b'file tree':{}},{b'name':b'../unsafe'},
                        {b'piece length':0},{b'pieces':b'x'},{b'length':-1},{b'private':2},
                        {b'files':[{b'path':[b'..'],b'length':3}]}]:
            invalid.append(bencode({b'info':{**INFO,**changes}}))
        invalid.append(bencode({b'info':INFO,b'url-list':b'https://example.org/payload'}))
        for raw in invalid:
            rpc=MagicMock()
            with self.assertRaises(torrent.Failure):torrent.run(['add-metainfo-stdin'],rpc,raw)
            rpc.call.assert_not_called()
        self.assertEqual(HASH,torrent.metainfo(RAW))
        rpc=MagicMock()
        with self.assertRaises(torrent.Failure): torrent.run(['add-metainfo-stdin','f'*40],rpc,RAW)
        rpc.call.assert_not_called()

    def test_multifile_and_private_tracker_policy(self):
        info={k:v for k,v in INFO.items() if k!=b'length'}
        info[b'files']=[{b'path':[b'a.txt'],b'length':1},{b'path':[b'b.txt'],b'length':2}]
        self.assertEqual(hashlib.sha1(bencode(info)).hexdigest(),torrent.metainfo(bencode({b'info':info})))
        for tracker in [b'http://127.0.0.1/announce', b'https://user:password@example.org/announce']:
            with self.assertRaises(torrent.Failure):torrent.metainfo(bencode({b'info':INFO,b'announce':tracker}))
        with patch.object(torrent.socket,'getaddrinfo',return_value=[(2,1,6,'',('10.0.0.1',80))]):
            with self.assertRaises(torrent.Failure):torrent.metainfo(bencode({b'info':INFO,b'announce':b'https://tracker.example.org/announce'}))


if __name__ == '__main__': unittest.main()
