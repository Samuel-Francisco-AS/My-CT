#!/usr/bin/env python3
"""Offline rootless Fedora regression. Only its own container is modified.
RPM fixtures: rpm-software-management/rpm commit 184507c8f690085c5917347a2cfd9722bcd8ee94,
tests/data/RPMS/. Download separately into the ignored output directory; never
commit binaries or import the RPM fixture's signing key.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_bundle import build
ROOT=Path(__file__).resolve().parents[3]
FIXTURES={'hello-2.0-1.x86_64.rpm':'e05a5191e214b1f05ae2448ebe493e55c6313ab68eaf040b83baa80e25f15d54',
'hello-2.0-1.x86_64-signed.rpm':'d32735351a8952afc9e4afd7309e3a1929288eff5af32526934314f1f304ff7f'}

def validate(output):
    output=Path(output).resolve();output.mkdir(parents=True,exist_ok=True)
    for name,sha in FIXTURES.items():
        if hashlib.sha256((output/name).read_bytes()).hexdigest()!=sha:raise ValueError('Unverified synthetic RPM fixture')
    bundle=output/'bundle';build(bundle)
    name='myct-audit-fix1-'+uuid.uuid4().hex[:10]
    def run(*args,check=True):
        p=subprocess.run(args,capture_output=True,timeout=180)
        if check and p.returncode:raise RuntimeError('Disposable command failed: '+str(args[:4])+'\n'+p.stdout.decode()[-2000:]+p.stderr.decode()[-1000:])
        return p
    run('podman','run','-d','--name',name,'--network=none','--systemd=always','--memory=1536m','--cpus=1','--pids-limit=1024','localhost/myct-setup4-test:latest')
    try:
        for _ in range(30):
            if run('podman','exec',name,'systemctl','show','--property=Version',check=False).returncode==0:break
            time.sleep(1)
        run('podman','cp',str(bundle/'payload.pyz'),name+':/tmp/setup.pyz')
        for rpm in FIXTURES:run('podman','cp',str(output/rpm),name+':/tmp/'+rpm)
        script=r'''
import hashlib,json,subprocess,zipfile
from pathlib import Path
with zipfile.ZipFile('/tmp/setup.pyz') as z:
 ns={'__name__':'fixture'};exec(compile(z.read('__main__.py'),'bundle','exec'),ns)
repo=Path('/tmp/repo-fixture');repo.mkdir()
conf=repo/'fixture.repo';results=[]
for metadata in (0,1):
 conf.write_text('[audit]\nname=Audit fixture\nbaseurl=file:///tmp/missing\ngpgcheck=0\npkg_gpgcheck=0\nrepo_gpgcheck='+str(metadata)+'\nenabled=0\n')
 before=hashlib.sha256(conf.read_bytes()).hexdigest()
 def config(flags):
  p=subprocess.run(['dnf','--setopt=reposdir='+str(repo),*flags,'--dump-repo-config=audit'],capture_output=True,text=True)
  assert p.returncode==0,p.stderr
  return dict(l.split(' = ',1) for l in p.stdout.splitlines() if ' = ' in l)
 old=config(['--setopt=gpgcheck=True']);fixed=config(ns['DNF_RPM_POLICY'])
 assert old['pkg_gpgcheck']=='0' and fixed['pkg_gpgcheck']=='1'
 assert fixed['repo_gpgcheck']==str(metadata)
 assert hashlib.sha256(conf.read_bytes()).hexdigest()==before
 results.append({'repo_metadata_policy':metadata,'old_pkg_gpgcheck':old['pkg_gpgcheck'],'fixed_pkg_gpgcheck':fixed['pkg_gpgcheck'],'configuration_unchanged':True})
rejected=[]
for filename in ('hello-2.0-1.x86_64.rpm','hello-2.0-1.x86_64-signed.rpm'):
 p=subprocess.run(['dnf','install','-y','--disable-repo=*',*ns['DNF_RPM_POLICY'],'/tmp/'+filename],capture_output=True,text=True,timeout=40)
 text=(p.stdout+p.stderr).lower()
 assert p.returncode!=0 and any(x in text for x in ('not signed','signature','public key','gpg')),text
 assert subprocess.run(['rpm','-q','hello'],capture_output=True).returncode!=0
 rejected.append({'fixture':filename,'exit':p.returncode,'signature_failure':True,'not_installed':True})
print(json.dumps({'effective_policy':results,'untrusted_rpms':rejected,'network':'none','arbitrary_keys_imported':False}))
'''
        # Source newlines in the repo string must remain escaped for the child Python.
        policy=run('podman','exec',name,'python3','-c',script)
        (output/'gpg-fedora.json').write_bytes(policy.stdout);print(policy.stdout.decode(),flush=True)
        run('podman','exec',name,'systemctl','start','user@1000.service')
        run('podman','exec',name,'loginctl','enable-linger','tester')
        run('podman','cp',str(ROOT/'ops'),name+':/tmp/ops')
        run('podman','cp',str(ROOT/'server'),name+':/tmp/server')
        run('podman','exec',name,'chown','-R','tester:tester','/tmp/ops')
        base=['podman','exec','--user','tester','--env','HOME=/home/tester','--env','XDG_RUNTIME_DIR=/run/user/1000','--env','DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus',name]
        for directory,label in (('/tmp/ops/setup/tests','setup'),('/tmp/ops/tests','operational')):
            result=run(*base,'python3','-m','unittest','discover','-s',directory,'-p','test_*.py','-v',check=False)
            (output/(label+'-fedora.log')).write_bytes(result.stdout+result.stderr)
            if result.returncode:raise RuntimeError(label+' suite failed; see its isolated log')
            print(label+': PASS; '+result.stderr.decode().split('----------------------------------------------------------------------')[-1].strip(),flush=True)
        (output/'container-result.json').write_text(json.dumps({'fedora':'44','architecture':'x86_64','network':'none','rpm_signatures':'PASS','setup':'PASS','operational':'PASS','physical_fedora':'NOT VALIDATED','personal_data':'NOT ACCESSED','memory_limit_mib':1536},indent=2)+'\n')
    finally:run('podman','rm','-f',name,check=False)

if __name__=='__main__':validate(sys.argv[1])
