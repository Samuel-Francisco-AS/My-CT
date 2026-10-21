"""SETUP-2 guards; no host installation or service mutation."""
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_bundle import build

class AddonTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='myct-setup2-unit-');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);build(self.root/'bundle')
        self.m=types.ModuleType('setup2_test')
        with zipfile.ZipFile(self.root/'bundle/payload.pyz') as bundle:
            exec(compile(bundle.read('__main__.py'),'bundle','exec'),self.m.__dict__)
        for attribute,value in [('home',self.root/'alice')]:
            value.mkdir();p=patch.object(self.m.Path,attribute,return_value=value);p.start();self.addCleanup(p.stop)
        self.home=self.root/'alice'
    def fake_diagnosis(self,name,masked=False,processes=None):
        service=dict(LoadState='not-found',ActiveState='inactive',UnitFileState='',FragmentPath='',MainPID='',DropInPaths='')
        if masked:service['LoadState']='masked'
        original=self.m.read
        def read(path):
            return 'ID=fedora\nVERSION_ID=44\n' if str(path)=='/etc/os-release' else original(path)
        patches=[patch.object(self.m,'addon_service',return_value=service),patch.object(self.m,'addon_api',return_value={'ready':False}),
                 patch.object(self.m,'addon_processes',return_value=processes or []),patch.object(self.m,'read',side_effect=read),
                 patch.object(self.m,'ok',return_value=True),patch.object(self.m,'out',return_value='transmission-daemon 4.1.3'),
                 patch.object(self.m,'run',return_value=types.SimpleNamespace(stdout=b'',stderr=b'transmission-daemon 4.1.3')),
                 patch.object(self.m.socket,'socket')]
        for p in patches:p.start()
        try:return self.m.addon_diagnose(name)
        finally:
            for p in reversed(patches):p.stop()
    def test_diagnosis_writes_nothing_and_detects_legacy_without_adopting(self):
        for name in ('transmission','prowlarr'):
            before=sorted(str(p) for p in self.home.rglob('*'))
            result=self.fake_diagnosis(name)
            self.assertTrue(result['can_apply'],result['blockers']);self.assertFalse(result['inventory']['detected'])
            self.assertEqual(before,sorted(str(p) for p in self.home.rglob('*')))
            p=self.m.addon_paths(name);p['config'].mkdir(parents=True);(p['config']/'owner-data').write_text('existing')
            result=self.fake_diagnosis(name)
            self.assertFalse(result['can_apply']);self.assertTrue(result['inventory']['detected'])
            self.assertEqual('unmanaged',result['components'][1]['state'])
            self.assertTrue(any('sem adoção' in value for value in result['blockers']))
    def test_masked_unit_or_foreign_process_blocks_a_second_instance(self):
        for name in ('transmission','prowlarr'):
            for parameters in ({'masked':True},{'processes':['999999']}):
                result=self.fake_diagnosis(name,**parameters)
                self.assertTrue(result['inventory']['detected'])
                self.assertFalse(result['can_apply'])
                self.assertEqual('unmanaged',result['components'][1]['state'])
                self.assertFalse(self.m.addon_paths(name)['state'].exists())
    def test_invalid_or_obsolete_approval_cannot_instantiate_installer(self):
        for approval in (None,'0'*64):
            args=types.SimpleNamespace(component='transmission',action='apply',approved=approval)
            with patch.object(self.m,'addon_diagnose',return_value={'plan_id':'a'*64,'can_apply':True,'complete':False}),patch.object(self.m,'AddonInstaller') as installer:
                with self.assertRaises(self.m.SetupError):self.m.addon_main(args)
                installer.assert_not_called()
    def test_rechecks_fresh_plan_before_receipt_or_packages(self):
        installer=self.m.AddonInstaller({'plan_id':'a'*64,'facts':{'target':'prowlarr'}},bytearray())
        with patch.object(self.m,'addon_diagnose',return_value={'plan_id':'b'*64,'can_apply':True}),patch.object(installer,'sudo') as sudo:
            with self.assertRaises(self.m.SetupError):installer.apply()
            sudo.assert_not_called();self.assertFalse((installer.state/'owned.json').exists())
    def test_refuses_symlink_and_never_overwrites_helper(self):
        installer=self.m.AddonInstaller({'facts':{'target':'transmission'}},bytearray())
        file=self.home/'keep';file.write_bytes(b'owner')
        for path in (file,self.home/'link'):
            if path!=file:path.symlink_to(file)
            with self.assertRaises(self.m.SetupError):installer.publish(path,b'candidate')
        self.assertEqual(b'owner',file.read_bytes())
    def test_pins_both_official_architectures_and_refuses_unverified_download(self):
        for artifact in self.m.MANIFEST['addons']['prowlarr']['artifacts'].values():
            self.assertTrue(artifact['url'].startswith('https://github.com/Prowlarr/Prowlarr/releases/download/v2.6.5.5623/'))
            self.assertEqual(64,len(artifact['sha256']))
        opener=types.SimpleNamespace(open=lambda *a,**k:io.BytesIO(b'wrong'))
        with patch.object(self.m.urllib.request,'build_opener',return_value=opener):
            with self.assertRaisesRegex(self.m.SetupError,'SHA-256'):
                self.m.download_official({'url':'https://github.com/fixture','size':5,'sha256':'0'*64},self.root/'wrong')
        redirect=self.m.OfficialRedirect()
        for url in ('http://github.com/file','https://evil.example/file','https://github.com@evil.example/file'):
            with self.assertRaises(self.m.SetupError):redirect.redirect_request(None,None,302,'',{},url)
    def test_tar_traversal_symlink_and_devices_are_refused(self):
        for name,type_ in [('../escape',tarfile.REGTYPE),('/tmp/escape',tarfile.REGTYPE),('Prowlarr/link',tarfile.SYMTYPE),('Prowlarr/device',tarfile.CHRTYPE)]:
            archive=self.root/'bad.tar.gz'
            with tarfile.open(archive,'w:gz') as tar:
                entry=tarfile.TarInfo(name);entry.type=type_;tar.addfile(entry)
            with self.assertRaises(self.m.SetupError):self.m.extract_official(archive,self.root/'extracted')
            self.assertFalse((self.root/'extracted').exists())
    def test_service_control_does_not_restart_active_service_or_touch_base(self):
        installer=self.m.AddonInstaller({'facts':{'target':'transmission','user':'alice'}},bytearray())
        with patch.object(self.m,'addon_diagnose',return_value={'blockers':[]}),patch.object(self.m,'addon_service',return_value={'ActiveState':'active'}),patch.object(self.m,'out',return_value='yes'),patch.object(self.m,'checked') as commands,patch.object(installer,'sudo') as sudo:
            installer.services();sudo.assert_not_called()
        flattened=' '.join(' '.join(map(str,c.args[0])) for c in commands.call_args_list)
        self.assertNotIn('restart',flattened);self.assertNotIn(' start ',flattened)
        for name in ('core','postgres','prowlarr','rclone'):self.assertNotIn(name,flattened)
    def test_xml_rejects_public_permissions_without_returning_key(self):
        file=self.home/'config.xml';key='f'*32
        file.write_text('<Config><ApiKey>'+key+'</ApiKey></Config>');file.chmod(0o644)
        with self.assertRaises(ValueError):self.m.private_xml(file)
        file.chmod(0o600);self.assertEqual(key,self.m.private_xml(file).findtext('ApiKey'))

if __name__=='__main__':unittest.main()
