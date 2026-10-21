#!/usr/bin/env python3
"""Real DNF/systemd/local-copy proof in an explicitly disposable rootless Fedora.
No host mounts, credentials, Google Drive, real torrents or production actions.
"""
import hashlib
import base64
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_bundle import build

def command(*args,input=None,check=True):
    p=subprocess.run(args,input=input,capture_output=True)
    if check and p.returncode:
        raise RuntimeError(f'Fixture command failed {p.returncode}: {args[:5]}\n'+p.stdout.decode()[-4000:]+p.stderr.decode()[-1000:])
    return p

def validate(destination):
    name='myct-setup3-'+uuid.uuid4().hex[:10]
    command('podman','run','-d','--name',name,'--label','io.myct.setup.test=3','--systemd=always','--memory=1536m','--cpus=1','--pids-limit=1024','localhost/myct-setup1-test')
    def execute(*args,input=None,check=True,user='tester'):
        return command('podman','exec','-i','--user',user,'--env','HOME=/home/'+user,'--env','XDG_RUNTIME_DIR=/run/user/1000','--env','DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus',name,*args,input=input,check=check)
    def cli(action,*args,check=True,payload='/tmp/setup.pyz'):
        return execute('python3',payload,action,'--component','rclone',*args,input=b'\n',check=check)
    def diagnose():return json.loads(cli('diagnose').stdout)
    evidence={'environment':'rootless Fedora 44 container; x86_64','physical_clean_fedora':'NOT VALIDATED','google_drive':'NOT ACCESSED'}
    try:
        for _ in range(30):
            if execute('systemctl','show','--property=Version',check=False).returncode==0:break
            time.sleep(1)
        execute('systemctl','start','user@1000.service',user='root')
        with tempfile.TemporaryDirectory(prefix='bundle-',dir=destination.parent) as temporary:
            root=Path(temporary);build(root)
            evidence['payload_sha256']=hashlib.sha256((root/'payload.pyz').read_bytes()).hexdigest()
            command('podman','cp',str(root/'payload.pyz'),name+':/tmp/setup.pyz')
            first=diagnose();assert first['can_apply'] and not first['inventory']['installed'],first['blockers']
            assert cli('apply','--approved','0'*64,check=False).returncode==1
            assert execute('test','!','-e','/home/tester/.local/state/myct-setup/rclone',check=False).returncode==0
            evidence['missing_diagnosis_and_denied_approval']=True
            # Inject one deterministic failure immediately after the DNF package step.
            with zipfile.ZipFile(root/'payload.pyz') as source,zipfile.ZipFile(root/'interrupted.pyz','w') as out:
                for info in source.infolist():
                    data=source.read(info.filename)
                    if info.filename=='__main__.py':
                        data=data.replace(b"if __name__ == '__main__':",b"CloudInstaller.publish=lambda *args: (_ for _ in ()).throw(SetupError('Fixture: interrupcao apos pacote'))\nif __name__ == '__main__':")
                    out.writestr(info,data)
            command('podman','cp',str(root/'interrupted.pyz'),name+':/tmp/interrupted.pyz')
            failed=cli('apply','--approved',first['plan_id'],check=False,payload='/tmp/interrupted.pyz')
            assert failed.returncode==1,failed.stdout
            partial=diagnose();assert partial['inventory']['installed'] and partial['can_apply'],partial['blockers']
            assert any(row['state']=='failure' for row in partial['journal'])
            evidence['package_installed_then_failure_resumable']=True
            applied=cli('apply','--approved',partial['plan_id'])
            assert json.loads(applied.stdout.splitlines()[-1])['state']=='complete'
            ready=diagnose();assert ready['complete'] and ready['cloud']['config']['state']=='pending',ready
            evidence['installed_version']=ready['inventory']['version'];evidence['oauth_pending_is_complete_offline']=True
            snapshot=execute('sh','-c','find "$HOME/.local/bin" "$HOME/.config/myct" "$HOME/.local/state/myct-setup/rclone" -type f -exec sha256sum {} +').stdout
            cli('apply','--approved',ready['plan_id'])
            assert snapshot==execute('sh','-c','find "$HOME/.local/bin" "$HOME/.config/myct" "$HOME/.local/state/myct-setup/rclone" -type f -exec sha256sum {} +').stdout
            evidence['repeat_unchanged']=True
            # Private synthetic local backend, custom path/remote; no network credentials.
            fixture=r'''
import hashlib,json,os,pathlib,subprocess
home=pathlib.Path.home();config=home/'local-fixture.conf';remote=home/'local-backend';remote.mkdir()
config.write_text('[fixture]\ntype = alias\nremote = '+str(remote)+'\n');config.chmod(0o600)
selection=home/'.config/myct/cloud.json';selection.write_text(json.dumps({'remote':'fixture:','config':str(config)},sort_keys=True)+'\n');selection.chmod(0o600)
# This selection change is confined to this test fixture, never performed by setup.
source=home/'fixture.txt';source.write_bytes(b'My-CT SETUP-3 disposable local proof\n')
def call(*args):
 p=subprocess.run(['python3',str(home/'.local/bin/myct-archive'),*args],capture_output=True)
 assert p.returncode==0,p.stdout
 return json.loads(p.stdout)
archived=call('archive',str(source));restored=call('restore',archived['entry']['id'])
assert pathlib.Path(restored['path']).read_bytes()==source.read_bytes()
assert config.read_text().startswith('[fixture]')
print(json.dumps({'copy_restore_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'custom_remote_and_path':True,'original_preserved':source.exists()}))
'''
            # Raw source contains intentional literal escapes; normalize just the script newline escapes.
            fixture=fixture.replace('\\\\n','\\n')
            proof=json.loads(execute('python3','-c',fixture).stdout)
            evidence.update(proof)
            # Existing bridge + engine, selection is read in the transient worker,
            # not inherited from the submitting SSH-style process environment.
            execute('python3','-c',"import pathlib;pathlib.Path.home().joinpath('job-fixture.txt').write_bytes(b'local transient fixture')")
            identity=uuid.uuid4().hex
            request=base64.urlsafe_b64encode(json.dumps({'kind':'file','args':['archive','/home/tester/job-fixture.txt']}).encode()).decode().rstrip('=')
            execute('python3','/home/tester/.local/bin/myct-cloud','start',identity,request)
            for _ in range(30):
                task=json.loads(execute('python3','/home/tester/.local/bin/myct-cloud','task',identity).stdout)['task']
                if task['state']=='succeeded': break
                assert task['state'] not in ('failed','uncertain'),task
                time.sleep(1)
            else: raise AssertionError('cloud transient worker did not finish')
            evidence['existing_cloud_worker_with_custom_selection']=True
            # Confirm systemd transient job survives submitting SSH-style process exit.
            execute('systemd-run','--user','--quiet','--collect','--unit=myct-setup3-proof','/usr/bin/python3','-c',"import time,pathlib;time.sleep(2);pathlib.Path.home().joinpath('independent-proof').write_text('done')")
            for _ in range(20):
                if execute('test','-f','/home/tester/independent-proof',check=False).returncode==0:break
                time.sleep(1)
            else:raise AssertionError('transient work did not finish')
            evidence['systemd_run_independent']=True
            assert execute('loginctl','show-user','tester','--property=Linger','--value').stdout.strip()==b'yes'
            evidence['linger']=True
            evidence['resource_limits']={'ram_mib':1536,'cpus':1,'pids':1024}
            evidence['container_usage']=command('podman','stats','--no-stream','--format','{{.MemUsage}} / {{.CPU}}',name).stdout.decode().strip()
        destination.write_text(json.dumps(evidence,indent=2)+'\n')
        print(json.dumps(evidence,indent=2))
    finally:command('podman','rm','-f',name,check=False)

if __name__=='__main__':validate(Path(sys.argv[1]).resolve())
