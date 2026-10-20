"""No personal config, catalog, network, package or service mutations."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_bundle import build

class CloudTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.home=Path(self.tmp.name);build(self.home/'bundle')
        self.m=types.ModuleType('cloud_setup_fixture')
        with zipfile.ZipFile(self.home/'bundle/payload.pyz') as z:
            exec(compile(z.read('__main__.py'),'bundle','exec'),self.m.__dict__)
        p=patch.object(self.m.Path,'home',return_value=self.home);p.start();self.addCleanup(p.stop)
        p=patch.dict(os.environ,{},clear=True);p.start();self.addCleanup(p.stop)
        self.config=self.home/'custom.conf';self.selection={'remote':'alternate:','config':str(self.config)}
        self.secret='SYNTHETIC-CREDENTIAL-NOT-FOR-OUTPUT'
    def configure(self,mode=0o600):
        self.config.write_text('[alternate]\ntype = drive\ntoken = '+self.secret+'\nclient_secret = '+self.secret+'\n')
        self.config.chmod(mode)
    def diagnose(self,installed=True,linger=True,version=b'rclone v1.74.3\n'):
        original=self.m.read
        def read(path):
            return 'ID=fedora\nVERSION_ID=44\nPRETTY_NAME=Fedora\n' if str(path)=='/etc/os-release' else original(path)
        def which(name):return None if name=='rclone' and not installed else '/usr/bin/'+name
        with patch.object(self.m,'read',side_effect=read),patch.object(self.m.shutil,'which',side_effect=which),patch.object(self.m,'ok',return_value=True),patch.object(self.m,'out',return_value='yes' if linger else 'no'),patch.object(self.m,'cloud_local_command',return_value=subprocess.CompletedProcess([],0,version,b'')) as calls:
            plan=self.m.cloud_diagnose(self.selection)
            self.assertTrue(all(c.args[0]==['version'] for c in calls.call_args_list))
            return plan
    def test_passive_missing_and_existing_never_network_or_write(self):
        self.configure();before=self.config.read_bytes()
        for installed in (False,True):
            tree=sorted(str(p) for p in self.home.rglob('*'))
            plan=self.diagnose(installed)
            self.assertEqual(plan['inventory']['installed'],installed)
            self.assertEqual(plan['cloud']['online'],'not_checked')
            self.assertTrue(plan['cloud']['config']['configured'])
            self.assertNotIn(self.secret,json.dumps(plan))
            self.assertEqual(before,self.config.read_bytes())
            self.assertEqual(tree,sorted(str(p) for p in self.home.rglob('*')))
    def test_older_or_unknown_rclone_is_never_reinstalled(self):
        for version in (b'rclone v1.69.0',b'unknown'):
            plan=self.diagnose(version=version)
            self.assertTrue(plan['inventory']['installed'])
            self.assertFalse(plan['can_apply'])
            self.assertEqual(plan['facts']['missing_packages'],[])
            self.assertTrue(any(reason.startswith('incompatible:') for reason in plan['blockers']))

    def test_missing_alternate_encrypted_and_protected_config(self):
        self.assertEqual(self.diagnose()['cloud']['config']['state'],'pending')
        self.configure(0o644)
        self.assertEqual(self.diagnose()['cloud']['config']['state'],'protected')
        self.configure();self.config.write_text('RCLONE_ENCRYPT_V0:\n'+self.secret)
        self.assertEqual(self.diagnose()['cloud']['config']['state'],'protected')
        self.config.write_text('[alternate]\ntype = sftp\npassword = '+self.secret)
        self.assertEqual(self.diagnose()['cloud']['config']['state'],'incompatible')
        self.config.write_text('[other]\ntype = drive\ntoken = '+self.secret)
        self.assertEqual(self.diagnose()['cloud']['config']['state'],'pending')
    def test_legacy_helpers_and_selection_are_preserved(self):
        path=self.m.cloud_paths()[1]/'myct-archive';path.parent.mkdir(parents=True);path.write_text(self.secret)
        plan=self.diagnose();self.assertFalse(plan['can_apply'])
        self.assertEqual(plan['cloud']['helpers']['myct-archive'],'previous')
        self.assertEqual(path.read_text(),self.secret)
        self.assertNotIn(self.secret,json.dumps(plan))
        path.unlink();selection=self.m.cloud_selection_file();selection.parent.mkdir(parents=True)
        selection.write_text(json.dumps({'remote':'original:','config':str(self.config)}));selection.chmod(0o600)
        self.assertFalse(self.diagnose()['can_apply']);self.assertIn('original:',selection.read_text())
    def test_unsafe_selection_and_links_block(self):
        for remote,config in [('bad:path',''),('x:','relative'),('x:','/a\nb'),('x:','/a/../b')]:
            with self.assertRaises(ValueError):self.m.cloud_validate_selection(remote,config)
        self.config.symlink_to(self.home/'missing')
        self.assertEqual(self.diagnose()['cloud']['config']['state'],'protected')
    def test_online_requires_fresh_specific_approval(self):
        self.configure();plan=self.diagnose()
        with patch.object(self.m,'cloud_diagnose',return_value=plan),patch.object(self.m,'cloud_local_command') as call:
            with self.assertRaises(self.m.SetupError):self.m.cloud_verify(self.selection,'wrong')
            call.assert_not_called()
    def test_online_errors_are_sanitized_and_no_transfer_commands(self):
        self.configure();plan=self.diagnose()
        for stderr,code in [(b'invalid_grant token='+self.secret.encode(),'oauth'),(b'network secret='+self.secret.encode(),'network'),(b'quota 403 '+self.secret.encode(),'quota')]:
            with patch.object(self.m,'cloud_diagnose',return_value=plan),patch.object(self.m,'cloud_local_command',return_value=subprocess.CompletedProcess([],1,b'',stderr)) as call:
                result=self.m.cloud_verify(self.selection,plan['plan_id'])
                self.assertEqual(result['code'],code);self.assertNotIn(self.secret,json.dumps(result))
                self.assertEqual(call.call_args.args[0][0],'about')
    def test_malformed_quota_and_timeout_fail_closed(self):
        self.configure();plan=self.diagnose()
        with patch.object(self.m,'cloud_diagnose',return_value=plan):
            for reply in (subprocess.CompletedProcess([],0,b'not-json '+self.secret.encode(),b''),
                          subprocess.CompletedProcess([],0,b'{"free":-1}',b''),
                          subprocess.CompletedProcess([],0,b'{"free":"secret"}',b'')):
                with patch.object(self.m,'cloud_local_command',return_value=reply):
                    result=self.m.cloud_verify(self.selection,plan['plan_id'])
                    self.assertEqual(result['code'],'response');self.assertNotIn(self.secret,json.dumps(result))
        with patch.object(self.m.subprocess,'run',side_effect=subprocess.TimeoutExpired('rclone',10)):
            self.assertEqual(self.m.cloud_local_command(['version']).returncode,124)

    def test_online_quota_and_folder_are_not_archive_recovery(self):
        self.configure();plan=self.diagnose()
        for folder in (True,False):
            responses=[subprocess.CompletedProcess([],0,b'{"free":0,"total":100}',b''),subprocess.CompletedProcess([],0,json.dumps({'IsDir':folder,'Name':self.secret}).encode(),b'')]
            with patch.object(self.m,'cloud_diagnose',return_value=plan),patch.object(self.m,'cloud_local_command',side_effect=responses) as calls:
                result=self.m.cloud_verify(self.selection,plan['plan_id'])
                self.assertFalse(result['archive_recoverable']);self.assertEqual(result['archive_accessible'],folder)
                self.assertEqual(result['quota_free'],0);self.assertNotIn(self.secret,json.dumps(result))
                self.assertEqual([c.args[0][0] for c in calls.call_args_list],['about','lsjson'])
                self.assertIn('--stat',calls.call_args_list[1].args[0])
    def test_approval_denied_or_changed_never_installs(self):
        plan=self.diagnose(False)
        installer=self.m.CloudInstaller(plan,bytearray())
        with patch.object(self.m,'cloud_diagnose',return_value={**plan,'plan_id':'0'*64}),patch.object(installer,'sudo') as sudo:
            with self.assertRaises(self.m.SetupError):installer.apply()
            sudo.assert_not_called();self.assertFalse((installer.state/'owned.json').exists())
    def test_reexecution_and_resume_preserve_config_and_helpers(self):
        self.configure();original=self.config.read_bytes()
        with zipfile.ZipFile(self.home/'bundle/payload.pyz') as z:
            for name in self.m.MANIFEST['cloud']['helpers']:
                path=self.m.cloud_paths()[1]/name;path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(z.read('ops/'+name));path.chmod(0o600 if name.endswith('.py') else 0o700)
        selected=self.m.cloud_selection_file();selected.parent.mkdir(parents=True)
        selected.write_text(json.dumps(self.selection,sort_keys=True)+'\n');selected.chmod(0o600)
        plan=self.diagnose();self.assertTrue(plan['complete'],plan['blockers']);self.assertFalse(plan['can_apply'])
        self.assertFalse(plan['inventory']['managed']) # Exact preexisting helpers are usable, never claimed.
        before=sorted((str(p),p.stat().st_mtime_ns) for p in self.home.rglob('*'))
        self.assertEqual(plan['plan_id'],self.diagnose()['plan_id'])
        self.assertEqual(before,sorted((str(p),p.stat().st_mtime_ns) for p in self.home.rglob('*')))
        self.assertEqual(original,self.config.read_bytes())
        (self.m.cloud_paths()[1]/'myct_cloud_progress.py').unlink()
        self.assertTrue(self.diagnose()['can_apply'])
    def test_invalid_selection_and_lock_links_do_not_hide_inspection_or_write(self):
        selected=self.m.cloud_selection_file();selected.parent.mkdir(parents=True)
        selected.write_text('invalid nonsecret selection');selected.chmod(0o600)
        with patch.object(self.m,'cloud_selection',side_effect=self.m.CloudConfigError('protected')):
            # Caller can still inspect with an explicit safe selection; changes remain blocked.
            self.assertFalse(self.diagnose()['can_apply'])
        selected.unlink()
        plan=self.diagnose(False);installer=self.m.CloudInstaller(plan,bytearray())
        installer.state.parent.mkdir(parents=True)
        protected=self.home/'protected';protected.write_text('keep')
        (installer.state.parent/'lock').symlink_to(protected)
        with patch.object(installer,'sudo') as sudo:
            with self.assertRaises(self.m.SetupError):installer.apply()
            sudo.assert_not_called()
        self.assertEqual(protected.read_text(),'keep')

    def test_online_process_environment_strips_secret_overrides(self):
        with patch.dict(os.environ,{'RCLONE_CONFIG_PASS':self.secret,'RCLONE_DUMP':'auth','RCLONE_DRIVE_TOKEN':self.secret}),patch.object(self.m.subprocess,'run',return_value=subprocess.CompletedProcess([],0,b'',b'')) as run:
            self.m.cloud_local_command(['version'])
            self.assertFalse(any(k.startswith('RCLONE_') for k in run.call_args.kwargs['env']))
            self.assertEqual(run.call_args.kwargs['stdin'],subprocess.DEVNULL)

if __name__=='__main__':unittest.main()
