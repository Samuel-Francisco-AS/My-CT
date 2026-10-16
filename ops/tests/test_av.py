"""Deterministic adapter tests: simulated engine output, no network or real scans."""
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

loader = importlib.machinery.SourceFileLoader('myct_av', str(Path(__file__).resolve().parents[1] / 'myct-av'))
spec = importlib.util.spec_from_loader(loader.name, loader)
av = importlib.util.module_from_spec(spec); loader.exec_module(av)


def raw(code=0, text='stdin: OK\nScanned files: 1\n', reason='', signal=0):
    return dict(exit_code=code, engine_output=text, incomplete_reason=reason, signal=signal)


class AntivirusContract(unittest.TestCase):
    def test_clean_requires_scanned_file(self):
        self.assertEqual(av.classify(raw())[0], 'CLEAN')
        self.assertEqual(av.classify(raw(text='stdin: Empty file\nScanned files: 0'))[0], 'INCOMPLETE')

    def test_detections_and_partial_results(self):
        threat = 'stdin: Win.Test.EICAR_HDB-1 FOUND\n'
        self.assertEqual(av.classify(raw(1, threat))[:2], ('DETECTED', ['Win.Test.EICAR_HDB-1']))
        for partial in ('Heuristics.Limits.Exceeded.MaxFiles', 'Heuristics.Encrypted.Zip', 'Heuristics.Broken.Executable'):
            result = av.classify(raw(1, threat + f'stdin: {partial} FOUND\n'))
            self.assertEqual(result[0], 'INCOMPLETE')
            self.assertEqual(result[1], ['Win.Test.EICAR_HDB-1'])

    def test_warnings_signals_timeout_and_error_never_clean(self):
        for data in (raw(text='stdin: OK\nScanned files: 1\nWARNING: scan issue'),
                     raw(signal=9), raw(reason='cancelled'), raw(2, 'execution failed')):
            self.assertNotEqual(av.classify(data)[0], 'CLEAN')
        self.assertEqual(av.classify({'error': 'INCOMPLETE: oversized'})[0], 'INCOMPLETE')
        self.assertEqual(av.classify({'error': 'permission denied'})[0], 'ERROR')

    def test_engine_and_database_failure(self):
        self.assertEqual(av.classify(raw(127, ''))[0], 'UNAVAILABLE')
        self.assertEqual(av.classify(raw(2, "LibClamAV Error: cli_loaddb: Malformed database"))[0], 'UNAVAILABLE')
        self.assertEqual(av.classify(raw(2, 'ERROR: stdin read failed'))[0], 'ERROR')

    def test_lock_prevents_scan_update_overlap(self):
        with tempfile.TemporaryDirectory() as d, patch.object(av, 'STATE', Path(d)):
            with av.locked():
                with self.assertRaises(BlockingIOError):
                    with av.locked():
                        self.fail('Second worker acquired lock')

    def test_manual_request_not_success_and_interrupted_state(self):
        data = {'result': 'RUNNING', 'last_success': 1}
        with tempfile.TemporaryDirectory() as d, patch.object(av, 'STATE', Path(d)), \
             patch.object(av, 'service', return_value={'ActiveState': 'failed'}), \
             patch.object(av, 'info', return_value={}), patch.object(av, 'load', return_value=data):
            self.assertEqual(av.update_status()['result'], 'INTERRUPTED')

    def test_freshness_uses_daily_and_successful_check(self):
        now = av.time.time()
        db = [{'name': 'main', 'timestamp': 1}, {'name': 'daily', 'timestamp': now}, {'name': 'bytecode', 'timestamp': 1}]
        with patch.object(av, 'engine_info', return_value='ClamAV'), patch.object(av, 'databases', return_value=db), \
             patch.object(av, 'load', return_value={'last_success': now}):
            self.assertFalse(av.info()['stale'])
            db[1]['timestamp'] = now - 73*3600
            self.assertTrue(av.info()['stale'])

    def test_update_failure_retains_last_success(self):
        with tempfile.TemporaryDirectory() as d, patch.object(av, 'STATE', Path(d)), \
             patch.object(av, 'load', return_value={'last_success': 123}), \
             patch.object(av.subprocess, 'run', return_value=subprocess.CompletedProcess([], 52)):
            result = av.update_worker()
            self.assertEqual(result['result'], 'ERROR')
            self.assertEqual(result['last_success'], 123)
            self.assertEqual(result['exit_code'], 52)
            self.assertIsNotNone(result['last_finished'])
            self.assertEqual(json.loads((Path(d) / 'update.json').read_text())['result'], 'ERROR')

    def test_units_are_oneshot_and_36h_inactive(self):
        root = Path(__file__).resolve().parents[1] / 'av'
        self.assertIn('OnUnitInactiveSec=36h', (root / 'myct-av-update.timer').read_text())
        self.assertIn('OnStartupSec=5min', (root / 'myct-av-update.timer').read_text())
        self.assertIn('Type=oneshot', (root / 'myct-av-update.service').read_text())
