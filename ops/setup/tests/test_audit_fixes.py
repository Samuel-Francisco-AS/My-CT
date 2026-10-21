"""Post-audit regressions; synthetic HOME only. No host services, sudo or SQL."""
import hashlib
import io
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import sys
import unittest
from unittest.mock import patch
import zipfile
import test_recovery


class AuditFixTests(unittest.TestCase):
    def setUp(self):
        self.f = test_recovery.RecoveryTests(); self.f.setUp(); self.addCleanup(self.f.doCleanups)
        self.m, self.home = self.f.m, self.f.home

    def test_same_rpm_policy_and_sanitized_failure_for_all_installers(self):
        m=self.m
        for installer in (m.Installer({},bytearray()),m.AddonInstaller({'facts':{'target':'transmission'}},bytearray()),m.CloudInstaller({},bytearray())):
            with self.subTest(installer=type(installer).__name__),patch.object(installer,'sudo') as sudo:
                installer.packages(['fixture-package'])
                args=sudo.call_args.args[0]
                for flag in ('--setopt=gpgcheck=1','--setopt=*.gpgcheck=1','--setopt=localpkg_gpgcheck=1'):
                    self.assertIn(flag,args)
                self.assertFalse(any('repo_gpgcheck=' in flag or 'nogpgcheck' in flag for flag in args))
            with patch.object(installer,'sudo',side_effect=m.SetupError('SYNTHETIC-PRIVATE-ERROR')):
                with self.assertRaises(m.SetupError) as error: installer.packages(['fixture-package'])
                self.assertIn('assinatura RPM',str(error.exception)); self.assertNotIn('SYNTHETIC-PRIVATE',str(error.exception))

    def crash(self,operation,point):
        m=self.m; child=os.fork()
        if child==0:
            resource.setrlimit(resource.RLIMIT_CORE,(0,0))
            if point=='partial_write':
                resource.setrlimit(resource.RLIMIT_FSIZE,(4096,4096));signal.signal(signal.SIGXFSZ,signal.SIG_DFL)
                operation();os._exit(0)
            rename=m.rename_noreplace;fsync=m.os.fsync;unlink=m.os.unlink
            def publication(*args):
                if point=='before_publish':os._exit(99)
                rename(*args)
                if point=='after_publish':os._exit(99)
            def flush(fd):
                fsync(fd)
                if point=='after_fsync' and __import__('stat').S_ISDIR(os.fstat(fd).st_mode):os._exit(99)
            def cleanup(*args,**kwargs):
                if point=='cleanup':os._exit(99)
                return unlink(*args,**kwargs)
            with patch.object(m,'rename_noreplace',side_effect=publication),patch.object(m.os,'fsync',side_effect=flush),patch.object(m.os,'unlink',side_effect=cleanup):operation()
            os._exit(0)
        _,status=os.waitpid(child,0)
        self.assertIn(os.waitstatus_to_exitcode(status),(-signal.SIGXFSZ,99))

    def test_recovery_publication_survives_abrupt_exit_at_each_boundary(self):
        m=self.m
        for point in ('before_publish','after_publish','after_fsync','cleanup'):
            request={'path':str(self.home/'backups'/point/'backup.zip')}
            request['approved']=m.rec_export_plan(request)['plan_id']
            self.crash(lambda:m.rec_export(request),point)
            target=Path(request['path'])
            if point=='before_publish':
                self.assertFalse(target.exists());m.rec_export(request)
            self.assertEqual(target.stat().st_nlink,1)
            m.rec_open(request,bytearray()) # A real package, not just arbitrary bytes.
            self.assertEqual(target.stat().st_mode&0o777,0o600)

    def test_helper_partial_write_and_publication_crashes_are_resumable(self):
        m=self.m;data=b'fixture-data\n'*10000
        for name in ('transmission','rclone'):
            installer=m.CloudInstaller({},bytearray()) if name=='rclone' else m.AddonInstaller({'facts':{'target':name}},bytearray())
            for point in ('partial_write','before_publish','after_publish','after_fsync','cleanup'):
                target=self.home/'.local/bin'/(name+'-'+point)
                self.crash(lambda:installer.publish(target,data),point)
                if point in ('partial_write','before_publish'):self.assertFalse(target.exists())
                installer.publish(target,data)
                self.assertEqual(target.read_bytes(),data);self.assertEqual(target.stat().st_nlink,1)
                before=target.stat().st_mtime_ns;installer.publish(target,data)
                self.assertEqual(before,target.stat().st_mtime_ns)
                self.assertEqual(json.loads((installer.state/'files.json').read_text())[str(target)],hashlib.sha256(data).hexdigest())

    def test_noreplace_and_descriptor_guards_preserve_conflicts(self):
        m=self.m;target=self.home/'private/file';m.atomic_user_write(target,b'owner')
        with self.assertRaises(FileExistsError):m.atomic_user_write(target,b'candidate')
        self.assertEqual(target.read_bytes(),b'owner')
        link=target.with_name('link');link.symlink_to(target)
        with self.assertRaises(m.SetupError):m.atomic_user_write(link,b'candidate')
        hard=target.with_name('hard');os.link(target,hard)
        with self.assertRaises(m.SetupError):m.rec_bytes(target)
        with self.assertRaises(m.SetupError):m.open_user_file(target,os.O_RDONLY)
        self.assertEqual(target.read_bytes(),b'owner')

    def test_unsafe_ancestors_and_global_lock_block_every_installer_before_sudo(self):
        m=self.m;root=m.paths()[1];root.mkdir(parents=True,mode=0o700)
        for unsafe in ('permissions','symlink','hardlink','fifo'):
            with self.subTest(unsafe=unsafe):
                victim=self.home/'victim';victim.write_bytes(b'keep');lock=root/'lock'
                if unsafe=='permissions':root.chmod(0o777)
                elif unsafe=='symlink':lock.symlink_to(victim)
                elif unsafe=='hardlink':os.link(victim,lock)
                else:os.mkfifo(lock)
                for installer in (m.Installer({'plan_id':'unused'},bytearray()),m.AddonInstaller({'facts':{'target':'transmission'}},bytearray()),m.CloudInstaller({},bytearray())):
                    with patch.object(installer,'sudo') as sudo:
                        with self.assertRaises((m.SetupError,OSError)):installer.apply()
                        sudo.assert_not_called()
                self.assertEqual(victim.read_bytes(),b'keep')
                if lock.exists() or lock.is_symlink():lock.unlink()
                root.chmod(0o700)

    def test_parent_symlink_inserted_during_walk_is_not_followed(self):
        m=self.m;parent=self.home/'safe';parent.mkdir();outside=self.f.root/'outside';outside.mkdir()
        original=m.os.open;changed=False
        def raced(path,*args,**kwargs):
            nonlocal changed
            if path=='safe' and not changed:
                parent.rename(self.home/'old-safe');parent.symlink_to(outside);changed=True
            return original(path,*args,**kwargs)
        with patch.object(m.os,'open',side_effect=raced):
            with self.assertRaises(OSError):m.atomic_user_write(parent/'file',b'candidate')
        self.assertFalse((outside/'file').exists())

    def test_cloud_preview_exposes_remapping_and_binds_changes_to_approval(self):
        self.f.config();_,backup=self.f.export();request,old=self.f.target(backup['path']);m=self.m
        preview,_,_=m.rec_restore_plan(request,bytearray());selection=preview['facts']['cloud_selection']
        self.assertEqual(selection['source']['remote'],'alternate:');self.assertEqual(selection['result']['remote'],'alternate:')
        self.assertTrue(selection['source']['config'].startswith(str(old)))
        self.assertTrue(selection['result']['config'].startswith(str(self.f.home)))
        self.assertTrue(selection['will_apply']);self.assertEqual(selection['status'],'apply')
        request['approved']=preview['plan_id']
        destination=m.cloud_selection_file();destination.parent.mkdir(parents=True,mode=0o700)
        destination.write_text(json.dumps({'remote':'owner:','config':''},sort_keys=True)+'\n');destination.chmod(0o600)
        changed,_,_=m.rec_restore_plan(request,bytearray());self.assertFalse(changed['can_apply'])
        self.assertNotEqual(preview['plan_id'],changed['plan_id'])
        self.assertEqual(changed['facts']['cloud_selection']['active_before']['remote'],'owner:')
        with self.assertRaises(m.SetupError):m.rec_restore(request,bytearray())
        self.assertIn('owner:',destination.read_text())

    def test_historical_cloud_selection_is_explicitly_not_applied(self):
        self.f.config();_,backup=self.f.export();request,_=self.f.target(backup['path']);m=self.m
        (self.f.home/'.local/share/myct/cloud-jobs').mkdir(parents=True,mode=0o700)
        plan,destinations,_=m.rec_restore_plan(request,bytearray());selection=plan['facts']['cloud_selection']
        self.assertEqual(selection['status'],'historical');self.assertFalse(selection['will_apply'])
        self.assertIsNone(selection['result']);self.assertNotIn(m.cloud_selection_file(),destinations)

    def test_optional_component_order_and_real_legacy_detection(self):
        # Execute the real publication/approval flows with synthetic RPM/systemd/API
        # responses. This is a cross-stage contract test, not a Fedora installation.
        m=self.m;empty=dict(LoadState='not-found',ActiveState='inactive',UnitFileState='',FragmentPath='',MainPID='',DropInPaths='')
        services={}
        def service(name,user_scope=True):return services.get(name,empty).copy() if user_scope else empty.copy()
        def command(args,*a,**kw):
            return subprocess.CompletedProcess(args,0,b'rclone v1.74.3\n',b'')
        def api(name):
            running=services.get('myct-'+name,{}).get('ActiveState')=='active'
            return {'ready':running,'version':'4.1.3','dedicated':True,'hook':True}
        def checked(args,**kwargs):
            self.assertEqual(args[:2],['systemctl','--user'])
            state=services.setdefault('myct-transmission',empty.copy())
            state.update(LoadState='loaded',FragmentPath=str(m.addon_paths('transmission')['unit']))
            if 'enable' in args:state['UnitFileState']='enabled'
            if 'start' in args:state.update(ActiveState='active',MainPID='999')
            return b''
        from types import SimpleNamespace
        with patch.object(m,'addon_service',side_effect=service),patch.object(m,'addon_api',side_effect=api),patch.object(m,'addon_processes',return_value=[]),patch.object(m,'ok',return_value=True),patch.object(m,'out',return_value='yes'),patch.object(m.shutil,'which',side_effect=lambda n: None if n=='transmission-daemon' else '/fixture/'+n),patch.object(m.socket,'socket'),patch.object(m,'cloud_local_command',side_effect=command),patch.object(m,'checked',side_effect=checked),patch.object(m,'emit'),patch.object(m.sys,'argv',[str(self.f.root/'bundle/payload.pyz')]):
            for order in (('transmission','rclone'),('rclone','transmission'),('rclone','prowlarr')):
                services.clear();home=self.home/('-'.join(order));home.mkdir(mode=0o700)
                with patch.object(m.Path,'home',return_value=home):
                    marker=home/'base-sentinel';marker.write_bytes(b'preserve Base')
                    selection={'remote':'fixture:','config':str(home/'custom.conf')}
                    for name in order:
                        if name=='rclone':
                            plan=m.cloud_diagnose(selection);self.assertTrue(plan['can_apply'],plan['blockers'])
                            m.CloudInstaller(plan,bytearray()).apply()
                            ready=m.cloud_diagnose(selection);self.assertTrue(ready['complete'],ready['blockers'])
                            before={str(p):p.read_bytes() for p in home.rglob('*') if p.is_file()}
                            m.cloud_main(SimpleNamespace(remote=selection['remote'],config=selection['config'],action='apply',approved=ready['plan_id']))
                            self.assertEqual(before,{str(p):p.read_bytes() for p in home.rglob('*') if p.is_file()})
                        else:
                            plan=m.addon_diagnose(name);self.assertTrue(plan['can_apply'],plan['blockers'])
                            self.assertFalse(plan['inventory']['detected'])
                            if name=='transmission':
                                m.AddonInstaller(plan,bytearray()).apply()
                                ready=m.addon_diagnose(name);self.assertTrue(ready['complete'],ready['blockers'])
                                before={str(p):p.read_bytes() for p in home.rglob('*') if p.is_file()}
                                m.addon_main(SimpleNamespace(component=name,action='apply',approved=ready['plan_id']))
                                self.assertEqual(before,{str(p):p.read_bytes() for p in home.rglob('*') if p.is_file()})
                    self.assertEqual(marker.read_bytes(),b'preserve Base')
            # A divergent companion or a true legacy configuration still blocks adoption.
            services.clear();home=self.home/'legacy';home.mkdir(mode=0o700)
            with patch.object(m.Path,'home',return_value=home):
                helper=home/'.local/bin/myct-torrent';helper.parent.mkdir(parents=True,mode=0o700)
                helper.write_bytes(b'divergent');self.assertFalse(m.addon_diagnose('transmission')['can_apply']);helper.unlink()
                config=m.addon_paths('transmission')['config'];config.mkdir(parents=True)
                (config/'settings.json').write_text('{}')
                self.assertFalse(m.addon_diagnose('transmission')['can_apply'])

if __name__=='__main__':unittest.main()
