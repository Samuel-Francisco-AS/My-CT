"""Synthetic recovery only: no host DB, cloud, service mutation or private fixtures."""
import io
import json
import os
from pathlib import Path
import pwd
import stat
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_bundle import build

class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.home=self.root/'source';self.home.mkdir(mode=0o700)
        build(self.root/'bundle');self.m=types.ModuleType('recovery_fixture')
        with zipfile.ZipFile(self.root/'bundle/payload.pyz') as z: exec(compile(z.read('__main__.py'),'bundle','exec'),self.m.__dict__)
        p=patch.object(self.m.Path,'home',side_effect=lambda:self.home);p.start();self.addCleanup(p.stop)
        p=patch.dict(os.environ,{},clear=True);p.start();self.addCleanup(p.stop)
        original_read=self.m.read
        def read(path):
            return 'ID=fedora\nVERSION_ID=44\n' if str(path)=='/etc/os-release' else original_read(path)
        p=patch.object(self.m,'read',side_effect=read);p.start();self.addCleanup(p.stop)
        self.secret='SYNTHETIC-PRIVATE-CANARY'
        p=patch.object(self.m,'addon_service',return_value={'LoadState':'not-found','UnitFileState':''});p.start();self.addCleanup(p.stop)
        p=patch.object(self.m.shutil,'which',return_value=None);p.start();self.addCleanup(p.stop)
    def config(self):
        path=self.home/'.config/myct-transmission/settings.json';path.parent.mkdir(parents=True,mode=0o700)
        path.write_text(json.dumps({'rpc-bind-address':'127.0.0.1','rpc-port':19091,'download-dir':str(self.home/'Downloads/My-CT/Torrents'),'rpc-password':self.secret}));path.chmod(0o600)
        path=self.home/'.local/share/myct/prowlarr/config.xml';path.parent.mkdir(parents=True,mode=0o700)
        path.write_text('<Config><BindAddress>127.0.0.1</BindAddress><Port>9696</Port><EnableSsl>False</EnableSsl><ApiKey>'+self.secret+'</ApiKey></Config>');path.chmod(0o600)
        path=self.m.cloud_selection_file();path.parent.mkdir(parents=True,mode=0o700,exist_ok=True)
        path.write_text(json.dumps({'remote':'alternate:','config':str(self.home/'.config/rclone/private.conf')},sort_keys=True)+'\n');path.chmod(0o600)
    def export(self):
        request={'operation':'plan-export','path':str(self.home/'backups/test.myct.zip')}
        plan=self.m.rec_export_plan(request);request['approved']=plan['plan_id']
        return request,self.m.rec_export(request)
    def target(self,package):
        old=self.home;self.home=self.root/'target';self.home.mkdir(mode=0o700,exist_ok=True)
        dest=self.home/'backup.zip';dest.write_bytes(Path(package).read_bytes());dest.chmod(0o600)
        return {'path':str(dest)},old
    def test_basic_roundtrip_remaps_without_secrets_or_db_and_repeats(self):
        self.config()
        with patch.object(self.m,'rec_sql') as sql:
            request,result=self.export();sql.assert_not_called()
        raw=Path(result['path']).read_bytes();self.assertNotIn(self.secret.encode(),raw)
        self.assertEqual(Path(result['path']).stat().st_mode&0o777,0o600)
        meta,files,basic=self.m.rec_unpack(raw)
        self.assertEqual(set(files),{'basic.json'});self.assertFalse(meta['private'])
        inspect,old=self.target(result['path'])
        plan,_,_=self.m.rec_restore_plan(inspect,bytearray());self.assertTrue(plan['can_apply'])
        self.assertEqual(plan['facts']['mapping']['old_home'],str(old))
        self.assertEqual(plan['facts']['mapping']['new_home'],str(self.home))
        inspect['approved']=plan['plan_id'];self.m.rec_restore(inspect,bytearray())
        selection=json.loads(self.m.cloud_selection_file().read_text());self.assertTrue(selection['config'].startswith(str(self.home)))
        plan,_,_=self.m.rec_restore_plan(inspect,bytearray());inspect['approved']=plan['plan_id']
        before={str(p):p.stat().st_mtime_ns for p in self.home.rglob('*')}
        repeat=self.m.rec_restore(inspect,bytearray())
        self.assertTrue(all(state=='existing' for state in repeat['completed']))
        self.assertEqual(before,{str(p):p.stat().st_mtime_ns for p in self.home.rglob('*')})
    def test_diagnosis_and_planning_never_export_or_restore(self):
        before=list(self.home.rglob('*'))
        with patch.object(self.m,'rec_sql') as sql,patch.object(self.m,'rec_publish') as publish:
            inventory=self.m.rec_inventory()
            self.m.rec_export_plan({'path':str(self.home/'new/test.zip')})
            sql.assert_not_called();publish.assert_not_called()
        self.assertEqual(before,list(self.home.rglob('*')));self.assertTrue(inventory['exclusions'])
    def test_no_mutation_operation_is_dispatchable_from_diagnostic_action(self):
        for operation in ('plan-export','inspect','export','restore'):
            stdin=types.SimpleNamespace(buffer=io.BytesIO(json.dumps({'operation':operation,'approved':'a'*64}).encode()+b'\n\n'))
            with patch.object(self.m.sys,'stdin',stdin),patch.object(self.m,'rec_restore') as restore,patch.object(self.m,'rec_export') as export,patch.object(self.m,'emit') as emit:
                self.m.backup_main('diagnose')
                self.assertEqual(emit.call_args.args[0]['operation'],'inventory')
                restore.assert_not_called();export.assert_not_called()
                self.assertEqual(stdin.buffer.tell(),0)

    def test_private_without_age_or_recipient_fails_closed(self):
        for category in ('database','catalogs'):
            with self.assertRaises(self.m.SetupError):self.m.rec_export_plan({'path':str(self.home/'b.zip'),category:True})
        self.assertFalse((self.home/'b.zip').exists())
    def test_recipient_never_echoes_private_identity_even_for_basic(self):
        with self.assertRaises(self.m.SetupError) as error:
            self.m.rec_export_plan({'path':str(self.home/'b.zip'),'recipient':self.secret})
        self.assertNotIn(self.secret,str(error.exception))

    def test_unapproved_or_stale_export_never_creates_package(self):
        request={'path':str(self.home/'backups/b.zip'),'approved':'0'*64}
        with self.assertRaises(self.m.SetupError):self.m.rec_export(request)
        self.assertFalse((self.home/'backups').exists())
    def test_partial_export_not_published_and_can_retry(self):
        request={'path':str(self.home/'backups/b.zip')};request['approved']=self.m.rec_export_plan(request)['plan_id']
        # Failure at the new no-replace publication syscall, before the final name exists.
        with patch.object(self.m,'rename_noreplace',side_effect=OSError('synthetic interruption')):
            with self.assertRaises(OSError):self.m.rec_export(request)
        self.assertFalse(Path(request['path']).exists());self.assertFalse(list((self.home/'backups').glob('.myct-stage-*')))
        self.m.rec_export(request)
    def test_partial_restore_resumes_without_overwriting_and_conflicts_block(self):
        self.config();_,backup=self.export();request,_=self.target(backup['path'])
        plan,_,_=self.m.rec_restore_plan(request,bytearray());request['approved']=plan['plan_id']
        publish=self.m.rec_publish;calls=[]
        def interrupted(path,data):
            calls.append(path)
            if len(calls)==2:raise OSError('synthetic interruption')
            return publish(path,data)
        with patch.object(self.m,'rec_publish',side_effect=interrupted):
            with self.assertRaises(OSError):self.m.rec_restore(request,bytearray())
        plan,_,_=self.m.rec_restore_plan(request,bytearray())
        self.assertTrue(any(row['state']=='existing' for row in plan['facts']['destinations']))
        request['approved']=plan['plan_id'];self.m.rec_restore(request,bytearray())
        self.m.cloud_selection_file().write_text('{"remote":"owner:"}')
        plan,_,_=self.m.rec_restore_plan(request,bytearray());self.assertFalse(plan['can_apply'])
        with self.assertRaises(self.m.SetupError):self.m.rec_restore(request,bytearray())
        self.assertIn('owner:',self.m.cloud_selection_file().read_text())
    def test_corrupt_tampered_incompatible_and_missing_items_are_rejected(self):
        _,result=self.export();raw=Path(result['path']).read_bytes()
        with self.assertRaises(Exception):self.m.rec_unpack(raw[:30])
        with zipfile.ZipFile(io.BytesIO(raw)) as z:entries={name:z.read(name) for name in z.namelist()}
        for mutation in ('hash','schema','version','missing','migration'):
            changed=dict(entries);meta=json.loads(changed['manifest.json'])
            if mutation=='hash':changed['basic.json']+=b' '
            if mutation=='schema':meta['schema']=999
            if mutation=='version':meta['release']='unknown'
            if mutation=='missing':changed.pop('basic.json')
            if mutation=='migration':meta['migration']='0'*64
            changed['manifest.json']=json.dumps(meta).encode()
            with self.assertRaises(self.m.SetupError):self.m.rec_unpack(self.zip(changed))
    def zip(self,entries,mode=stat.S_IFREG|0o600,compression=zipfile.ZIP_STORED):
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w') as z:
            for name,data in entries.items():
                item=zipfile.ZipInfo(name);item.external_attr=mode<<16;item.compress_type=compression;z.writestr(item,data)
        return output.getvalue()
    def test_traversal_symlinks_devices_compression_and_limits_refused(self):
        for name in ('../escape','/absolute','catalogs/torrents/../../escape','extra.sh'):
            with self.assertRaises(self.m.SetupError):self.m.rec_unpack(self.zip({'manifest.json':b'{}',name:b'x'}))
        for mode in (stat.S_IFLNK|0o777,stat.S_IFCHR|0o600):
            with self.assertRaises(self.m.SetupError):self.m.rec_unpack(self.zip({'manifest.json':b'{}','basic.json':b'x'},mode))
        with self.assertRaises(self.m.SetupError):self.m.rec_unpack(self.zip({'manifest.json':b'{}','basic.json':b'x'*10000},compression=zipfile.ZIP_DEFLATED))
        with patch.object(self.m,'REC_ITEM',1):
            with self.assertRaises(self.m.SetupError):self.m.rec_unpack(self.zip({'manifest.json':b'{}','basic.json':b'xx'}))
    def test_dump_sql_is_never_executed_and_only_fixed_rows_are_accepted(self):
        rendered=b"SELECT malicious();\nCOPY public.execution_events (id, occurred_at, event_type) FROM stdin;\n1\t2026-10-20 12:00:00+00\tfixture.event\n\\.\nSELECT malicious();\n"
        with patch.object(self.m,'rec_bounded',return_value=rendered) as command,patch.object(self.m,'rec_sql') as sql:
            rows=self.m.rec_event_rows(b'synthetic dump');self.assertEqual(rows[0][2],'fixture.event')
            self.assertEqual(command.call_args.args[0][0],'pg_restore');sql.assert_not_called()
        for data in (rendered.replace(b'fixture.event',b"evil';SELECT"),rendered.replace(b'1\t',b'-1\t'),rendered.replace(b'2026-10-20 12:00:00+00',b'infinity')):
            with patch.object(self.m,'rec_bounded',return_value=data):
                with self.assertRaises(self.m.SetupError):self.m.rec_event_rows(b'dump')
    def test_existing_db_is_never_replaced_and_no_db_access_for_legacy(self):
        self.assertFalse(self.m.rec_db_owned())
        with patch.object(self.m,'rec_bounded') as commands:
            with self.assertRaises(self.m.SetupError):self.m.rec_sql('SELECT 1')
            commands.assert_not_called()
    def test_destination_symlinks_and_repository_are_refused(self):
        linked=self.home/'link';linked.symlink_to(self.root)
        for path in (linked/'b.zip',self.home/'Projetos/My-CT/b.zip',self.home/'../escape'):
            with self.assertRaises(self.m.SetupError):self.m.rec_path(str(path))
    def test_catalog_size_lock_and_fifo_guards_are_read_only(self):
        import fcntl
        root=self.home/'.local/share/myct/archive';root.mkdir(parents=True,mode=0o700)
        (root/'catalog.json').write_text('{"version":1,"entries":{}}')
        lock=root/'catalog.lock';lock.touch(mode=0o600)
        with patch.object(self.m,'REC_MAX',65537):
            with self.assertRaises(self.m.SetupError):self.m.rec_catalogs()
        with lock.open('rb') as handle:
            fcntl.flock(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with self.assertRaises(self.m.SetupError):self.m.rec_catalogs()
        lock.unlink();os.mkfifo(lock,0o600)
        with self.assertRaises(self.m.SetupError):self.m.rec_catalogs()
        self.assertTrue((root/'catalog.json').is_file())
    def test_existing_cloud_state_does_not_receive_a_new_selection(self):
        self.config();_,backup=self.export();request,_=self.target(backup['path'])
        jobs=self.home/'.local/share/myct/cloud-jobs';jobs.mkdir(parents=True,mode=0o700)
        plan,files,_=self.m.rec_restore_plan(request,bytearray())
        self.assertNotIn(self.m.cloud_selection_file(),files)
        self.assertTrue(any('Estado de nuvem anterior' in text for text in plan['warnings']))
        self.assertFalse(self.m.cloud_selection_file().exists())
    def test_all_git_destinations_are_refused_not_only_this_project(self):
        other=self.home/'other-repo';other.mkdir();(other/'.git').mkdir()
        with self.assertRaises(self.m.SetupError):self.m.rec_path(str(other/'private.zip'))

    def test_output_is_bounded(self):
        with self.assertRaises(self.m.SetupError):self.m.rec_bounded([sys.executable,'-c','print("x"*1000)'],limit=100)

if __name__=='__main__':unittest.main()
