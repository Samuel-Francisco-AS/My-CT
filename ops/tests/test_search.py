import contextlib
import http.client
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import unittest
from unittest.mock import MagicMock, patch
from urllib.parse import parse_qs, urlsplit

loader = importlib.machinery.SourceFileLoader('search', str(Path(__file__).parents[1] / 'myct-search'))
spec = importlib.util.spec_from_loader(loader.name, loader)
search = importlib.util.module_from_spec(spec)
loader.exec_module(search)

# Synthetic credentials only; never read the real Prowlarr config in unit tests.
KEY = '0123456789abcdef0123456789abcdef'
MAGNET = 'magnet:?xt=urn:btih:' + 'a' * 40 + '&dn=Ubuntu'
PROXY = 'http://127.0.0.1:9696/1/download?apikey=' + KEY + '&link=opaque&file=Ubuntu'


def row(**extra):
    return {'title': 'Ubuntu', 'protocol': 'torrent', 'size': 1234, 'indexer': 'Knaben',
            'indexerId': 1, 'seeders': 7, 'leechers': 0, 'magnetUrl': PROXY, **extra}


class FakeApi:
    key = KEY

    def __init__(self, rows=None, sources=None, unavailable=None):
        self.rows = [] if rows is None else rows
        self.calls = []
        self.sources = sources
        self.unavailable = unavailable or []

    def get(self, path, timeout=7):
        self.calls.append((path, timeout))
        if path == '/api/v1/indexerstatus':
            return self.unavailable
        if path == '/api/v1/indexer':
            if self.sources is not None:
                return self.sources
            return [{'id': 1, 'name': 'Knaben', 'enable': True, 'protocol': 'torrent'},
                    {'id': 2, 'name': 'Disabled', 'enable': False, 'protocol': 'torrent'},
                    {'id': 3, 'name': 'Usenet', 'enable': True, 'protocol': 'usenet'}]
        if path == '/api/v1/system/status':
            return {'appName': 'Prowlarr', 'version': '2.6.5.5623', 'secret': KEY}
        return self.rows


class SearchTest(unittest.TestCase):
    def test_status_and_get_only_torrent_search(self):
        api = FakeApi([row()])
        self.assertEqual('ready', search.run(['status'], api)['state'])
        result = search.run(['search', ' Ubuntu & Linux '], api)
        path, timeout = api.calls[-1]
        self.assertEqual(25, timeout)
        self.assertEqual('/api/v1/search', urlsplit(path).path)
        self.assertEqual({'query': ['Ubuntu & Linux'], 'type': ['search'],
                          'indexerIds': ['1']}, parse_qs(urlsplit(path).query))
        self.assertNotIn(KEY, path)
        self.assertEqual(1, result['count'])

    def test_real_field_shape_preserves_zero_and_omits_secret_urls(self):
        item = search.simplify(row(downloadUrl=PROXY, infoHash='a' * 40), KEY)
        self.assertEqual(('Ubuntu', 1234, 'Knaben', 7, 0),
                         tuple(item[k] for k in ('title', 'size_bytes', 'source', 'seeders', 'leechers')))
        self.assertEqual('magnet', item['link_type'])
        self.assertEqual('magnet:?xt=urn:btih:' + 'a' * 40, item['magnet'])
        self.assertEqual('infohash', item['magnet_source'])
        self.assertNotIn(KEY, json.dumps(item))
        self.assertNotIn('infoHash', item)

    def test_direct_magnets_are_preserved_and_prioritized(self):
        for field in ('magnetUrl', 'downloadUrl'):
            item = search.simplify(row(infoHash='b' * 40, **{field: MAGNET}), KEY)
            self.assertEqual(('magnet', MAGNET), (item['link_type'], item['magnet']))
            self.assertEqual('direct', item['magnet_source'])

    def test_infohash_accepts_only_complete_v1_hex_or_base32(self):
        valid = ['0123abcd' * 5, 'ABCDEF01' * 5,
                 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567', 'abcdefghijklmnopqrstuvwxyz234567']
        for infohash in valid:
            for direct in ('', PROXY, 'magnet:?xt=invalid'):
                with self.subTest(infohash=infohash, direct=direct):
                    item = search.simplify(row(magnetUrl=direct, infoHash=infohash), KEY)
                    self.assertEqual('magnet', item['link_type'])
                    self.assertEqual('infohash', item['magnet_source'])
                    self.assertEqual('magnet:?xt=urn:btih:' + infohash, item['magnet'])
                    self.assertEqual({'xt': ['urn:btih:' + infohash]}, parse_qs(urlsplit(item['magnet']).query))
        for infohash in [None, 123, [], {}, '', 'g' * 40, 'a' * 39, 'a' * 41,
                         'A' * 31, 'A' * 33, '0' * 32, '1' * 32, '8' * 32,
                         'A' * 31 + '=', ' ' + 'a' * 40, 'a' * 40 + '\n',
                         'urn:btih:' + 'a' * 40, 'a' * 40 + '&tr=https://example.org']:
            with self.subTest(infohash=infohash):
                item = search.simplify(row(infoHash=infohash), KEY)
                self.assertEqual('indirect_url', item['link_type'])
                self.assertIsNone(item['magnet'])
                self.assertIsNone(item['magnet_source'])

    def test_no_hash_keeps_null_magnet_and_existing_link_classification(self):
        for value, kind in [('', 'none'), (PROXY, 'indirect_url'),
                            ('https://example.org/ubuntu.torrent', 'torrent_url')]:
            item = search.simplify(row(magnetUrl=value), KEY)
            self.assertEqual(kind, item['link_type'])
            self.assertIsNone(item['magnet'])
            self.assertIsNone(item['magnet_source'])

    def test_infohash_fallback_does_not_echo_secrets_or_http_urls(self):
        for infohash in ('b' * 40, KEY + '12345678'):
            api = FakeApi([row(infoHash=infohash, downloadUrl=PROXY, infoUrl=PROXY)])
            with patch.object(search, 'api_key', return_value=KEY), patch.object(search, 'Api', return_value=api), \
                    contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(0, search.main(['search', 'Ubuntu']))
            payload = out.getvalue()
            self.assertNotIn(KEY, payload)
            self.assertNotIn('http', payload)
            self.assertNotIn('apikey', payload)
            self.assertEqual(1, json.loads(payload)['schema_version'])
        self.assertIsNone(search.simplify(row(infoHash=KEY + '12345678'), KEY)['magnet'])

    def test_torrent_url_page_and_malformed_links(self):
        self.assertEqual('torrent_url', search.link('https://example.org/ubuntu.torrent?x=1', KEY)[0])
        self.assertEqual('indirect_url', search.link('https://example.org/details/ubuntu', KEY)[0])
        for value in ('magnet:?xt=invalid', 'https://[', 'file:///tmp/test.torrent',
                      MAGNET + '\n', MAGNET + '&dn=' + KEY, 'magnet:?xt=urn:btih:' + 'a' * 39):
            self.assertEqual(('none', None), search.link(value, KEY))

    def test_missing_numeric_fields_are_null_not_zero(self):
        item = search.simplify(row(size=-1, seeders=True, leechers='5'), KEY)
        for field in ('size_bytes', 'seeders', 'leechers'):
            self.assertIsNone(item[field])
        self.assertIsNone(search.simplify({'protocol': 'torrent'}, KEY))
        self.assertIsNone(search.simplify(None, KEY))

    def test_empty_malformed_and_bounded_results(self):
        self.assertEqual('empty', search.run(['search', 'Ubuntu'], FakeApi())['state'])
        for rows in ({'error': KEY}, [None, {}, row(title='')]):
            with self.assertRaises(search.Failure) as caught:
                search.run(['search', 'Ubuntu'], FakeApi(rows))
            self.assertEqual('invalid_response', caught.exception.code)
        result = search.run(['search', 'Ubuntu'], FakeApi([None] + [row(title=f'Ubuntu {i}') for i in range(25)]))
        self.assertEqual((20, 26, 1, True),
                         tuple(result[k] for k in ('count', 'received_count', 'discarded_count', 'truncated')))

    def test_dynamic_sources_availability_and_id_validation(self):
        sources = [{'id': i, 'name': f'Source {i}', 'enable': True, 'protocol': 'torrent'} for i in (9, 1, 17)]
        api = FakeApi([row(indexerId=17)], sources, [{'indexerId': 9, 'disabledTill': '2999-01-01T00:00:00Z'}])
        status = search.run(['status'], api)
        self.assertEqual([9, 1, 17], [s['id'] for s in status['indexers']])
        self.assertEqual([False, True, True], [s['available'] for s in status['indexers']])
        search.run(['search', 'Ubuntu', '--indexer', '17'], api)
        self.assertEqual(['17'], parse_qs(urlsplit(api.calls[-1][0]).query)['indexerIds'])
        for source_id, code in [(2, 'invalid_indexer'), (9, 'indexer_unavailable'), (999, 'invalid_indexer')]:
            before = len([c for c in api.calls if c[0].startswith('/api/v1/search?')])
            with self.assertRaises(search.Failure) as caught:
                search.run(['search', 'Ubuntu', '--indexer', str(source_id)], api)
            self.assertEqual(code, caught.exception.code)
            self.assertEqual(before, len([c for c in api.calls if c[0].startswith('/api/v1/search?')]))
        with self.assertRaises(search.Failure) as caught:
            search.run(['status'], FakeApi(sources=[]))
        self.assertEqual('no_indexers', caught.exception.code)

    def test_pages_zero_twenty_twenty_one_281_and_limit(self):
        for count in (0, 1, 20, 21, 281, 501, 620):
            api = FakeApi([row(title=f'Ubuntu {i}') for i in range(count)])
            first = search.run(['search', 'Ubuntu'], api)
            self.assertEqual(min(count, 500), first['total_count'])
            self.assertEqual(count > 500, first['limited'])
            collected = []
            for page in range(1, max(1, (min(count, 500) + 19) // 20) + 1):
                result = search.run(['search', 'Ubuntu', '--page', str(page)], api)
                self.assertLessEqual(result['count'], 20)
                self.assertEqual(page * 20 < min(count, 500), result['has_more'])
                collected.extend(item['id'] for item in result['results'])
            self.assertEqual(min(count, 500), len(collected))
            self.assertEqual(len(collected), len(set(collected)))
        result = search.run(['search', 'Ubuntu', '--page', '25'], FakeApi([row()]))
        self.assertEqual(('empty', False), (result['state'], result['has_more']))

    def test_round_robin_deterministic_preserves_source_order_and_skips_zero(self):
        sources = [{'id': i, 'name': f'Source {i}', 'enable': True, 'protocol': 'torrent'} for i in (9, 1, 3, 5)]
        rows = [row(title=f'{i}-{n}', indexerId=i, indexer=f'Source {i}') for i, count in [(9, 3), (1, 30), (3, 4)] for n in range(count)]
        api = FakeApi(rows, sources)
        result = search.run(['search', 'Ubuntu'], api)
        self.assertEqual([1, 3, 9, 1, 3, 9, 1, 3, 9, 1, 3], [r['indexer_id'] for r in result['results'][:11]])
        self.assertEqual(result, search.run(['search', 'Ubuntu'], api))
        full = result['results'] + search.run(['search', 'Ubuntu', '--page', '2'], api)['results']
        self.assertEqual([f'1-{n}' for n in range(30)], [r['title'] for r in full if r['indexer_id'] == 1])
        filtered = search.run(['search', 'Ubuntu', '--indexer', '9'], api)
        self.assertEqual([9, 9, 9], [r['indexer_id'] for r in filtered['results']])
        self.assertEqual(34, filtered['discarded_count'])

    def test_duplicates_normalized_hashes_metadata_and_changing_peers(self):
        direct = row(title='Direct', magnetUrl='magnet:?xt=urn:btih:' + 'A' * 40)
        same = row(title='Another title', infoHash='a' * 40, seeders=999)
        base32 = row(infoHash='VKVKVKVKVKVKVKVKVKVKVKVKVKVKVKVK')
        result = search.run(['search', 'Ubuntu'], FakeApi([direct, same, base32, row(title='No hash'), row(title='No hash', seeders=500)]))
        self.assertEqual(2, result['count'])
        self.assertEqual(3, result['duplicate_count'])
        self.assertEqual('Direct', result['results'][0]['title'])

    def test_invalid_pagination_options_fail_before_credentials(self):
        for options in [ ['--page', '0'], ['--page', '-1'], ['--page', '26'], ['--indexer', '0'],
                         ['--indexer', '2147483648'], ['--page', '1;id'], ['--page', '1', '--page', '2'],
                         ['--offset', '20'], ['--indexer'], ['--page', '01'] ]:
            with patch.object(search, 'api_key') as key, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, search.main(['search', 'Ubuntu'] + options))
                key.assert_not_called()
        self.assertEqual(('Ubuntu', 2, 1), search.validate_args(['search', 'Ubuntu', '--indexer', '1', '--page', '2']))

    def test_direct_magnets_do_not_expose_tracker_credentials_or_http_in_metadata(self):
        item = search.simplify(row(title='Ubuntu https://site.invalid/passkey', magnetUrl=MAGNET + '&tr=https://tracker.invalid/PASSKEY/announce&xs=http://user:password@host/private'), KEY)
        output = json.dumps(item)
        for secret in ('https://', 'http://', 'PASSKEY', 'password'):
            self.assertNotIn(secret, output)
        self.assertEqual('direct', item['magnet_source'])
        self.assertEqual(MAGNET, item['magnet'])

    def test_query_validation_before_credentials_or_network(self):
        for args in ([], ['search'], ['search', ''], ['search', 'a' * 201],
                     ['search', 'Ubuntu\n'], ['search', 'one', 'two'], ['download', MAGNET]):
            with patch.object(search, 'api_key') as key, contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(2, search.main(args))
                key.assert_not_called()
                self.assertEqual('usage', json.loads(out.getvalue())['error']['code'])

    def test_private_config_and_key_not_in_diagnostics(self):
        xml = ('<Config><ApiKey>' + KEY + '</ApiKey></Config>').encode()
        for mode, uid, payload, expected in [
                (0o600, os.getuid(), xml, True), (0o644, os.getuid(), xml, False),
                (0o600, os.getuid() + 1, xml, False), (0o600, os.getuid(), b'<bad', False),
                (0o600, os.getuid(), b'<Config/>', False)]:
            meta = MagicMock(st_mode=stat.S_IFREG | mode, st_uid=uid, st_size=len(payload))
            stream = MagicMock()
            stream.__enter__.return_value = stream
            stream.fileno.return_value = 99
            stream.read.return_value = payload
            with patch.object(search.os, 'open', return_value=99), \
                    patch.object(search.os, 'fdopen', return_value=stream), \
                    patch.object(search.os, 'fstat', return_value=meta):
                if expected:
                    self.assertEqual(KEY, search.api_key())
                else:
                    with self.assertRaises(search.Failure) as caught:
                        search.api_key()
                    self.assertNotIn(KEY, str(caught.exception))

    def test_http_errors_timeout_and_size_are_sanitized_and_closed(self):
        cases = [(401, b'', 'authentication'), (403, b'', 'authentication'),
                 (302, b'', 'upstream'), (500, KEY.encode(), 'upstream'),
                 (200, KEY.encode(), 'invalid_response'),
                 (200, b'x' * (search.RESPONSE_LIMIT + 1), 'response_limit')]
        for status, payload, code in cases:
            conn = MagicMock()
            response = conn.getresponse.return_value
            response.status = status
            response.read.return_value = payload
            with patch.object(search.http.client, 'HTTPConnection', return_value=conn) as factory:
                with self.assertRaises(search.Failure) as caught:
                    search.Api(KEY).get('/api/v1/search?query=Ubuntu')
                self.assertEqual(code, caught.exception.code)
                self.assertNotIn(KEY, str(caught.exception))
                factory.assert_called_once_with('127.0.0.1', 9696, timeout=7)
                conn.close.assert_called_once()
        for error, code in [(TimeoutError(KEY), 'timeout'), (ConnectionRefusedError(KEY), 'connection'),
                            (http.client.IncompleteRead(b'secret'), 'connection')]:
            conn = MagicMock()
            conn.request.side_effect = error
            with patch.object(search.http.client, 'HTTPConnection', return_value=conn):
                with self.assertRaises(search.Failure) as caught:
                    search.Api(KEY).get('/api/v1/indexer')
                self.assertEqual(code, caught.exception.code)
                self.assertNotIn(KEY, str(caught.exception))
                conn.close.assert_called_once()

    def test_auth_header_and_no_redirect_or_retry(self):
        conn = MagicMock()
        conn.getresponse.return_value.status = 200
        conn.getresponse.return_value.read.return_value = b'[]'
        with patch.object(search.http.client, 'HTTPConnection', return_value=conn):
            self.assertEqual([], search.Api(KEY).get('/api/v1/search?query=Ubuntu'))
        conn.request.assert_called_once_with('GET', '/api/v1/search?query=Ubuntu',
                                             headers={'X-Api-Key': KEY, 'Accept': 'application/json'})
        conn.getresponse.return_value.read.assert_called_once_with(search.RESPONSE_LIMIT + 1)

    def test_json_envelope_and_redaction(self):
        api = FakeApi([row(title='Ubuntu ' + KEY, indexer=KEY)])
        with patch.object(search, 'api_key', return_value=KEY), patch.object(search, 'Api', return_value=api), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, search.main(['search', 'Ubuntu']))
        payload = out.getvalue()
        self.assertNotIn(KEY, payload)
        self.assertEqual(1, json.loads(payload)['schema_version'])
        self.assertIn('[redacted]', payload)
        with patch.object(search, 'api_key', return_value=KEY), \
                patch.object(search, 'run', side_effect=search.Failure('timeout', 'Tempo excedido.')), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(3, search.main(['status']))
            self.assertFalse(json.loads(out.getvalue())['ok'])

    def test_no_enabled_torrent_indexer(self):
        api = FakeApi()
        with patch.object(api, 'get', return_value=[{'enable': False, 'protocol': 'torrent', 'id': 1}]):
            with self.assertRaises(search.Failure) as caught:
                search.run(['search', 'Ubuntu'], api)
        self.assertEqual('no_indexers', caught.exception.code)


if __name__ == '__main__':
    unittest.main()
