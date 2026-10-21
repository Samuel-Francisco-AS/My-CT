"""Offline guard/transaction tests. No sudo, packages, services or host DB touched."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SETUP = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SETUP))
import build_bundle
spec = importlib.util.spec_from_file_location('setup_impl', SETUP / 'setup.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)

class GuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='myct-setup-tests-')
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        home = patch.object(setup.Path, 'home', return_value=self.home)
        home.start(); self.addCleanup(home.stop)
        self.bundle = self.home / 'bundle'
        build_bundle.build(self.bundle)
        with zipfile.ZipFile(self.bundle / 'payload.pyz') as file:
            namespace = {'__name__': 'setup_test'}
            exec(compile(file.read('__main__.py'), '__main__.py', 'exec'), namespace)
        setup.MANIFEST = namespace['MANIFEST']
        self.units = patch.object(setup, 'unit_path', return_value=self.home / '.config/systemd/user/myct-postgres.service')
        self.units.start()
        self.addCleanup(self.units.stop)

    def test_bundle_reproducible_with_source_integrity(self):
        first = (self.bundle / 'payload.pyz').read_bytes()
        build_bundle.build(self.bundle)
        self.assertEqual(first, (self.bundle / 'payload.pyz').read_bytes())
        import hashlib
        with zipfile.ZipFile(self.bundle / 'payload.pyz') as file:
            for name, expected in setup.MANIFEST['sha256'].items():
                self.assertEqual(expected, hashlib.sha256(file.read(name)).hexdigest())
        self.assertNotIn(b'User=sam', first)

    def test_denied_or_stale_approval_does_not_start_installer(self):
        for action, approved, can_apply in [('diagnose', None, True), ('apply', None, True),
                                           ('apply', 'old', True), ('apply', 'fresh', False)]:
            argv = ['setup.py', action] + (['--approved', approved] if approved else [])
            with patch.object(sys, 'argv', argv), patch.object(setup, 'diagnose', return_value={'plan_id': 'fresh', 'can_apply': can_apply, 'complete': False}), \
                 patch.object(setup, 'emit'), patch.object(setup, 'Installer') as installer:
                if action == 'diagnose':
                    setup.main()
                else:
                    with self.assertRaises(setup.SetupError):
                        setup.main()
                installer.assert_not_called()

    def test_fresh_plan_checked_again_under_lock_before_packages(self):
        state = self.home / 'state'
        with patch.object(setup, 'paths', return_value=(self.home / 'db', state)), \
             patch.object(setup, 'diagnose', return_value={'plan_id': 'changed', 'can_apply': True}):
            installer = setup.Installer({'plan_id': 'approved'}, bytearray())
            with patch.object(installer, 'sudo') as sudo:
                with self.assertRaisesRegex(setup.SetupError, 'Estado mudou'):
                    installer.apply()
                sudo.assert_not_called()
                self.assertFalse((state / 'owned.json').exists())

    def test_existing_destination_never_overwritten(self):
        state = self.home / 'state'; state.mkdir()
        source = self.home / 'source'; source.write_bytes(b'candidate')
        destination = self.home / 'existing'; destination.write_bytes(b'owner data')
        with patch.object(setup, 'paths', return_value=(self.home / 'db', state)):
            installer = setup.Installer({}, bytearray())
            with patch.object(installer, 'sudo') as sudo:
                with self.assertRaisesRegex(setup.SetupError, 'divergente'):
                    installer.install_file(source, destination, '0755')
                sudo.assert_not_called()
        self.assertEqual(b'owner data', destination.read_bytes())

    def test_unit_user_and_home_are_discovered_without_third_party_services(self):
        root = Path('/home/alice/.local/share/myct/postgres')
        core = setup.unit_text('core', root, 'alice')
        postgres = setup.unit_text('postgres', root, 'alice')
        for unit in (core, postgres):
            self.assertIn('Restart=on-failure', unit)
            self.assertNotIn('sam', unit)
            for excluded in ('prowlarr', 'transmission', 'rclone', 'jellyfin', 'postgresql.service'):
                self.assertNotIn(excluded, unit.lower())
        self.assertIn('User=alice\n', core)
        self.assertIn('WantedBy=multi-user.target', core)
        self.assertNotIn('User=', postgres)
        self.assertIn('WantedBy=default.target', postgres)
        self.assertIn('/usr/local/bin/myct-core', core)
        self.assertIn(str(root), postgres)

    def test_failed_migration_reports_error_and_never_drops_or_reinits(self):
        root = self.home / 'db'; (root / 'data').mkdir(parents=True)
        (root / 'data/PG_VERSION').write_text('18')
        (root / 'data/postgresql.conf').write_text('# preexisting owned cluster\n')
        state = self.home / 'state'; state.mkdir()
        work = self.home / 'work'; (work / 'server/migrations').mkdir(parents=True)
        (work / 'server/migrations/001_execution_events.sql').write_text(
            (build_bundle.ROOT / 'server/migrations/001_execution_events.sql').read_text())
        def query(pgroot, sql, database='myct'):
            if 'pg_database' in sql or sql == 'SELECT 1':
                return subprocess.CompletedProcess([], 0, b'1\n', b'')
            if 'to_regclass' in sql:
                return subprocess.CompletedProcess([], 0, b'f\n', b'')
            return subprocess.CompletedProcess([], 1, b'', b'')
        with patch.object(setup, 'paths', return_value=(root, state)), patch.object(setup, 'psql', side_effect=query) as queries:
            installer = setup.Installer({'facts': {'user': 'alice'}}, bytearray())
            with patch.object(installer, 'sudo'), patch.object(installer, 'install_file'), patch.object(setup, 'checked') as commands:
                with self.assertRaisesRegex(setup.SetupError, 'migração'):
                    installer.step_postgres(work)
                self.assertFalse(any(str(c.args[0][0]) in ('initdb', 'createdb') for c in commands.call_args_list))
            sqls = [call.args[1] for call in queries.call_args_list]
            migration = next(sql for sql in sqls if 'BEGIN;' in sql)
            self.assertIn('CREATE TABLE public.myct_setup_migrations', migration)
            self.assertIn('COMMIT;', migration)
            self.assertNotIn('DROP ', migration)
        self.assertEqual('18', (root / 'data/PG_VERSION').read_text())

    def test_libpq_address_service_and_password_overrides_are_removed(self):
        with patch.dict(setup.os.environ, {'PGSERVICE': 'untrusted', 'PGHOSTADDR': '192.0.2.1', 'PGPASSWORD': 'fixture-only'}), \
             patch.object(setup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'', b'')) as command:
            setup.run(['psql', '-X', '-w'])
        environment = command.call_args.kwargs['env']
        for name in ('PGSERVICE', 'PGHOSTADDR', 'PGPASSWORD'):
            self.assertNotIn(name, environment)
        self.assertEqual('3', environment['PGCONNECT_TIMEOUT'])

    def test_diagnose_reads_without_creating_private_state(self):
        real_read = setup.read
        def fixture_read(path):
            if str(path) == '/etc/os-release':
                return 'ID=fedora\nVERSION_ID=44\nPRETTY_NAME="Fedora fixture"\n'
            return real_read(path)
        units = dict(LoadState='not-found', ActiveState='inactive', UnitFileState='', FragmentPath='', User='')
        before = sorted(str(p) for p in self.home.rglob('*'))
        with patch.object(setup.Path, 'home', return_value=self.home), \
             patch.object(setup, 'read', side_effect=fixture_read), \
             patch.object(setup, 'health', return_value=False), \
             patch.object(setup, 'unit_state', return_value=units), \
             patch.object(setup, 'out', return_value=''), \
             patch.object(setup, 'ok', return_value=False), \
             patch.object(setup.socket, 'socket'), \
             patch.object(setup, 'Installer') as installer:
            result = setup.diagnose()
            self.assertEqual(1, result['schema'])
            self.assertEqual(5, len(result['components']))
            installer.assert_not_called()
        self.assertEqual(before, sorted(str(p) for p in self.home.rglob('*')))

    def test_sudo_credential_only_stdin_never_argv(self):
        with patch.object(setup, 'checked', return_value=b'') as checked:
            setup.Installer({}, bytearray(b'fixture-password')).sudo(['/usr/bin/systemctl', 'daemon-reload'])
        argv = checked.call_args.args[0]
        self.assertNotIn('fixture-password', repr(argv))
        self.assertEqual(b'fixture-password\n', checked.call_args.kwargs['input'])
        self.assertIn('-k', argv)

if __name__ == '__main__':
    unittest.main()
