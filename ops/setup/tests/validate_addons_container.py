#!/usr/bin/env python3
"""SETUP-2 real Fedora proof, explicit disposable rootless fixture; never a host installer."""
import argparse
import hashlib
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

ROOT=Path(__file__).resolve().parents[3]

def command(*args,input=None,check=True):
    p=subprocess.run(list(args),input=input,capture_output=True)
    if check and p.returncode:
        raise RuntimeError(f'Fixture command failed {p.returncode}: {args[:4]}\n'+p.stdout.decode(errors='replace')[-10000:]+p.stderr.decode(errors='replace')[-2000:])
    return p


def validate(destination):
    name='myct-setup2-'+uuid.uuid4().hex[:10]
    network=name+'-net'
    command('podman','network','create',network)
    command('podman','run','-d','--network',network,'--name',name,'--label','io.myct.setup.test=2','--systemd=always','--memory=1536m','--cpus=1','--pids-limit=1024','localhost/myct-setup1-test')
    def execute(*args,input=None,check=True,user='tester'):
        return command('podman','exec','-i','--user',user,'--env','HOME=/home/'+user,'--env','XDG_RUNTIME_DIR=/run/user/1000','--env','DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus',name,*args,input=input,check=check)
    def cli(target,action,*args,check=True,payload='/tmp/setup.pyz'):
        return execute('python3',payload,action,'--component',target,*args,input=b'\n',check=check)
    def diagnose(target):return json.loads(cli(target,'diagnose').stdout)
    def snapshot(target):
        p='/home/tester/.local/state/myct-setup/'+target
        paths=[p+'/events.jsonl','/home/tester/.config/systemd/user/myct-'+target+'.service',
               '/home/tester/'+('.config/myct-transmission/settings.json' if target=='transmission' else '.local/share/myct/prowlarr/config.xml')]
        return {'pid':execute('systemctl','--user','show','myct-'+target+'.service','--property=MainPID','--value').stdout.decode().strip(),
                'hashes':execute('sha256sum',*paths).stdout.decode()}
    result={'fedora':'44','arch':'x86_64','base_provisioned':False,'components':{}}
    try:
        for _ in range(30):
            if execute('systemctl','show','--property=Version',check=False).returncode==0:break
            time.sleep(1)
        execute('systemctl','start','user@1000.service',user='root')
        with tempfile.TemporaryDirectory(prefix='bundle-',dir=destination.parent) as temporary:
            bundle=Path(temporary);build(bundle)
            result['payload_sha256']=hashlib.sha256((bundle/'payload.pyz').read_bytes()).hexdigest()
            command('podman','cp',str(bundle/'payload.pyz'),name+':/tmp/setup.pyz')
            for target in ('transmission','prowlarr'):
                first=diagnose(target)
                assert first['can_apply'] and not first['inventory']['detected'],first['blockers']
                denied=cli(target,'apply','--approved','0'*64,check=False)
                assert denied.returncode==1
                assert execute('test','!','-e','/home/tester/.local/state/myct-setup/'+target,check=False).returncode==0
                # Simulate a failure after packages and before component publication; then resume normally.
                if target=='prowlarr':
                    interrupted=bundle/'interrupted.pyz'
                    with zipfile.ZipFile(bundle/'payload.pyz') as source,zipfile.ZipFile(interrupted,'w') as out:
                        for info in source.infolist():
                            data=source.read(info.filename)
                            if info.filename=='__main__.py':
                                data=data.replace(b"if __name__ == '__main__':",b"AddonInstaller.component=lambda *args: (_ for _ in ()).throw(SetupError('Falha simulada SETUP-2'))\nif __name__ == '__main__':")
                            out.writestr(info,data)
                    command('podman','cp',str(interrupted),name+':/tmp/interrupted.pyz')
                    failure=cli(target,'apply','--approved',first['plan_id'],payload='/tmp/interrupted.pyz',check=False)
                    assert failure.returncode==1
                    first=diagnose(target)
                    assert first['can_apply'],first['blockers']
                    assert any(event['state']=='failure' for event in first['journal'])
                    result['failure_resume']=True
                applied=cli(target,'apply','--approved',first['plan_id'])
                assert json.loads(applied.stdout.splitlines()[-1])['state']=='complete'
                ready=diagnose(target);assert ready['complete'],ready['blockers']
                before=snapshot(target)
                repeated=cli(target,'apply','--approved',ready['plan_id'])
                assert json.loads(repeated.stdout.splitlines()[-1])['state']=='complete'
                assert before==snapshot(target)
                result['components'][target]={'inventory':ready['inventory'],'repeat_no_changes':True,
                    'unit_memory':execute('systemctl','--user','show','myct-'+target+'.service','--property=MemoryCurrent').stdout.decode().strip()}
                # No key may appear in diagnostic/install/progress output or receipt journal.
                if target=='prowlarr':
                    key=execute('python3','-c',"import xml.etree.ElementTree as E;print(E.parse('/home/tester/.local/share/myct/prowlarr/config.xml').findtext('ApiKey'))").stdout.strip()
                    assert key not in applied.stdout and key not in cli(target,'diagnose').stdout
                    assert key not in execute('cat','/home/tester/.local/state/myct-setup/prowlarr/events.jsonl').stdout
            torrents=json.loads(execute('/home/tester/.local/bin/myct-torrent','list').stdout)
            assert torrents['torrents']==[]
            result['torrent_helper_empty_list']=True
            status=execute('/home/tester/.local/bin/myct-search','status',check=False)
            status=json.loads(status.stdout)
            assert status['error']['code']=='no_indexers',status
            search=execute('/home/tester/.local/bin/myct-search','search','myct fixture',check=False)
            assert json.loads(search.stdout)['error']['code']=='no_indexers'
            result['search_helper']='no_indexers (expected; no sources added)'
            # A tiny synthetic v1 fixture without tracker or web seeds, container network disabled before adding.
            execute('sh','-c',"cat > /tmp/helper-fixture.py",input=FIXTURE.encode())
            command('podman','network','disconnect',network,name)
            fixture=json.loads(execute('python3','/tmp/helper-fixture.py').stdout)
            result['torrent_controls']=fixture
            # Inspect real sockets (hex 0100007F is 127.0.0.1) without requiring extra diagnostic packages.
            sockets=execute('python3','-c',"from pathlib import Path;print(Path('/proc/net/tcp').read_text());print(Path('/proc/net/tcp6').read_text())").stdout.decode().splitlines()
            for port in (19091,9696):
                listeners=[line for line in sockets if f':{port:04X} ' in line and ' 0A ' in line]
                assert len(listeners)==1 and f'0100007F:{port:04X}' in listeners[0],listeners
            result['loopback_sockets']=True
            for path in ('/usr/local/bin/myct-core','/home/tester/.local/share/myct/postgres'):
                assert execute('test','!','-e',path,check=False).returncode==0
            result['no_base_changes']=True
            result['prowlarr_disk']=execute('du','-sh','/home/tester/.local/opt/Prowlarr','/home/tester/.local/share/myct/prowlarr').stdout.decode().strip()
            # Restore network after the offline torrent fixture; ordinary boot includes network.
            command('podman','network','connect',network,name)
            command('podman','restart',name)
            # Re-upload after boot: /tmp staging is intentionally ephemeral.
            command('podman','cp',str(bundle/'payload.pyz'),name+':/tmp/setup.pyz')
            for _ in range(90):
                if all(diagnose(target)['complete'] for target in ('transmission','prowlarr')):break
                time.sleep(1)
            else:raise AssertionError('Services not recovered after container boot: '+json.dumps({target:{k:v for k,v in diagnose(target).items() if k in ('inventory','blockers')} for target in ('transmission','prowlarr')}))
            result['container_boot']=True
        destination.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
    finally:
        command('podman','rm','-f',name,check=False)
        command('podman','network','rm',network,check=False)

FIXTURE=r'''
import hashlib,json,runpy,time
m=runpy.run_path('/home/tester/.local/bin/myct-torrent');rpc=m['Rpc']()
def b(v):
 if isinstance(v,bytes):return str(len(v)).encode()+b':'+v
 if isinstance(v,int):return b'i'+str(v).encode()+b'e'
 return b'd'+b''.join(b(k)+b(x) for k,x in sorted(v.items()))+b'e'
info={b'name':b'MYCT synthetic offline fixture.txt',b'length':1,b'piece length':16384,b'pieces':hashlib.sha1(b'x').digest()}
raw=b({b'info':info});key=hashlib.sha1(b(info)).hexdigest()
added=m['run'](['add-metainfo-stdin'],rpc,raw=raw)
assert added['hash']==key
for verb in ('pause','status','resume','pause'):
 m['run']([verb,key],rpc)
# Preserve a small file through helper removal (no actual peer or torrent payload downloaded).
folder=m['destination']()/key
(folder/'preserved.fixture').write_bytes(b'fixture')
m['run'](['remove',key],rpc)
assert (folder/'preserved.fixture').read_bytes()==b'fixture'
print(json.dumps({'add_pause_resume_remove':True,'preserves_data':True,'offline_fixture':True}))
'''
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);args=parser.parse_args()
    validate(Path(args.output))
