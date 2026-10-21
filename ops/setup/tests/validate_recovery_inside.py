"""Runs ONLY in the disposable container created by validate_recovery_container.py.
No personal paths, credentials, Google Drive or media. Never run on the host.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import types
import zipfile

assert Path('/run/.containerenv').exists(), 'This fixture requires an isolated container'
BASE=Path('/home/tester')
SOURCE=BASE/'source';TARGET=BASE/'target'
PAYLOAD=Path('/tmp/setup.pyz')

def command(args,home,input=None,check=True):
    env={k:v for k,v in os.environ.items() if not k.startswith('PG')};env['HOME']=str(home)
    p=subprocess.run(args,input=input,capture_output=True,env=env)
    if check and p.returncode:raise RuntimeError('Synthetic fixture command failed: '+str(args[:2])+' '+p.stdout.decode()[-1500:]+p.stderr.decode()[-800:])
    return p

def cli(home,request,identity=b'',check=True):
    p=command(['python3',str(PAYLOAD),'backup','--component','backup'],home,
              input=json.dumps(request).encode()+b'\n'+identity+b'\n',check=check)
    return json.loads(p.stdout),p.returncode

def sql(home,text):
    root=home/'.local/share/myct/postgres'
    return command(['psql','-X','-w','-A','-t','-h',str(root/'run'),'-U','tester','-d','myct','-v','ON_ERROR_STOP=1'],home,text.encode()).stdout

def prepare(home):
    home.mkdir(mode=0o700)
    root=home/'.local/share/myct/postgres';root.mkdir(parents=True,mode=0o700);(root/'run').mkdir(mode=0o700)
    command(['initdb','-D',str(root/'data'),'--auth-local=peer','--auth-host=reject','--no-instructions'],home)
    with (root/'data/postgresql.conf').open('a') as f:f.write("\nlisten_addresses=''\nunix_socket_directories='"+str(root/'run')+"'\nshared_buffers='16MB'\nmax_connections=10\n")
    command(['pg_ctl','-D',str(root/'data'),'-l',str(root/'fixture.log'),'-w','start'],home)
    command(['createdb','-w','-h',str(root/'run'),'-U','tester','myct'],home)
    with zipfile.ZipFile(PAYLOAD) as z:
        namespace={'__name__':'recovery_fixture_module'};exec(compile(z.read('__main__.py'),'bundle','exec'),namespace)
        migration=z.read('server/migrations/001_execution_events.sql').decode()
        sha=namespace['MANIFEST']['sha256']['server/migrations/001_execution_events.sql']
    sql(home,migration+"\nCREATE TABLE public.myct_setup_migrations(version integer PRIMARY KEY,sha256 text NOT NULL); INSERT INTO public.myct_setup_migrations VALUES(1,'"+sha+"');")
    state=home/'.local/state/myct-setup';state.mkdir(parents=True,mode=0o700)
    owner=state/'owned.json';owner.write_text(json.dumps({'schema':1,'user':'tester','root':str(root)},sort_keys=True));owner.chmod(0o600)
    return namespace

unit=BASE/'.config/systemd/user/myct-transmission.service';unit.parent.mkdir(parents=True,mode=0o700)
unit.write_text('[Unit]\nDescription=Synthetic existing unit - preserve\n[Service]\nExecStart=/usr/bin/true\n');unit.chmod(0o600)
command(['systemctl','--user','daemon-reload'],BASE)
unit_hash=hashlib.sha256(unit.read_bytes()).hexdigest()
namespace=prepare(SOURCE);prepare(TARGET)
sql(SOURCE,"INSERT INTO public.execution_events(occurred_at,event_type) VALUES('2026-10-20 12:00:00+00','fixture.one'),('2026-10-20 12:00:01+00','fixture.two');")
secret='SYNTHETIC-SECRET-EXCLUDED'
config=SOURCE/'.config/myct-transmission/settings.json';config.parent.mkdir(parents=True,mode=0o700)
config.write_text(json.dumps({'rpc-bind-address':'127.0.0.1','rpc-port':19091,'download-dir':str(SOURCE/'Downloads/My-CT/Torrents'),'rpc-password':secret}));config.chmod(0o600)
config=SOURCE/'.local/share/myct/prowlarr/config.xml';config.parent.mkdir(parents=True,mode=0o700)
config.write_text('<Config><BindAddress>127.0.0.1</BindAddress><Port>9696</Port><EnableSsl>False</EnableSsl><ApiKey>'+secret+'</ApiKey></Config>');config.chmod(0o600)
config=SOURCE/'.config/rclone/rclone.conf';config.parent.mkdir(parents=True,mode=0o700)
config.write_text('[personal]\ntype=drive\ntoken='+secret);config.chmod(0o600)
selection=SOURCE/'.config/myct/cloud.json';selection.parent.mkdir(parents=True,mode=0o700)
selection.write_text(json.dumps({'remote':'fixture-drive:','config':str(config)},sort_keys=True)+'\n');selection.chmod(0o600)
root=SOURCE/'.local/share/myct/archive';root.mkdir(mode=0o700)
(root/'catalog.json').write_text(json.dumps({'version':1,'entries':{}}));(root/'catalog.json').chmod(0o600);(root/'catalog.lock').touch(mode=0o600)
# Synthetic identity never printed, committed or persisted in Git/release.
key=next(line for line in command(['age-keygen'],SOURCE).stdout.splitlines() if line.startswith(b'AGE-SECRET-KEY-1'))
recipient=command(['age-keygen','-y'],SOURCE,key+b'\n').stdout.decode().strip()
before_secret=hashlib.sha256(config.read_bytes()).hexdigest()
inventory,_=cli(SOURCE,{'operation':'inventory'});assert inventory['age'] and inventory['categories'][1]['available']
# Public basic package cannot contain either marker or a dump.
basic_request={'operation':'plan-export','path':str(SOURCE/'backups/basic.myct.zip')}
basic_plan,_=cli(SOURCE,basic_request);basic_request.update(operation='export',approved=basic_plan['plan_id'])
basic,_=cli(SOURCE,basic_request);raw=Path(basic['path']).read_bytes()
assert secret.encode() not in raw
with zipfile.ZipFile(io.BytesIO(raw)) as z:assert set(z.namelist())=={'manifest.json','basic.json'}
# Logical dump and selected catalog are always encrypted.
request={'operation':'plan-export','path':str(SOURCE/'backups/private.myct.age'),'database':True,'catalogs':True,'recipient':recipient}
plan,_=cli(SOURCE,request);assert plan['can_apply']
denied={**request,'operation':'export','approved':'0'*64};_,code=cli(SOURCE,denied,check=False);assert code==1 and not Path(request['path']).exists()
request.update(operation='export',approved=plan['plan_id']);result,_=cli(SOURCE,request)
package=Path(result['path']).read_bytes();assert package.startswith(b'age-encryption.org/v1\n') and secret.encode() not in package
assert hashlib.sha256(config.read_bytes()).hexdigest()==before_secret
copy=TARGET/'selected.myct.age';copy.write_bytes(package);copy.chmod(0o600)
restore={'operation':'inspect','path':str(copy),'media_root':str(TARGET/'Different-media')}
_,code=cli(TARGET,restore,check=False);assert code==1 # identity is never assumed/stored.
inspection,_=cli(TARGET,restore,key);assert inspection['can_apply'],inspection
assert inspection['facts']['database']=='empty'
assert any(row['name']=='myct-transmission' and row['target_present'] for row in inspection['facts']['components'])
assert inspection['facts']['mapping']['old_home']==str(SOURCE) and inspection['facts']['mapping']['new_home']==str(TARGET)
# Inspection never inserts rows or extracts anything.
assert sql(TARGET,'SELECT count(*) FROM public.execution_events;').strip()==b'0'
assert not (TARGET/'.local/share/myct/recovery').exists()
restore.update(operation='restore',approved=inspection['plan_id']);restored,_=cli(TARGET,restore,key)
assert restored['state']=='complete'
assert sql(TARGET,'SELECT id,occurred_at,event_type FROM public.execution_events ORDER BY id;')==sql(SOURCE,'SELECT id,occurred_at,event_type FROM public.execution_events ORDER BY id;')
assert not (TARGET/'.config/rclone/rclone.conf').exists()
assert json.loads((TARGET/'.config/myct/cloud.json').read_text())['config']==str(TARGET/'.config/rclone/rclone.conf')
assert (Path(restored['path'])/'catalogs/files/catalog.json').read_bytes()==(SOURCE/'.local/share/myct/archive/catalog.json').read_bytes()
assert not (TARGET/'.local/share/myct/archive').exists() # Historical, not activated.
# Reinspect and replay changes no recovered bytes or database rows.
restore['operation']='inspect';inspection,_=cli(TARGET,restore,key);assert inspection['facts']['database']=='existing'
before={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in Path(restored['path']).rglob('*') if p.is_file()}
restore.update(operation='restore',approved=inspection['plan_id']);repeat,_=cli(TARGET,restore,key)
assert all(v=='existing' for v in repeat['completed'])
assert before=={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in Path(restored['path']).rglob('*') if p.is_file()}
# Existing data conflict and PostgreSQL major mismatch fail closed.
sql(TARGET,"INSERT INTO public.execution_events(event_type) VALUES('target.keep');")
restore['operation']='inspect';conflict,_=cli(TARGET,restore,key);assert not conflict['can_apply'] and conflict['facts']['database']=='conflict'
# Version incompatibility is refused after valid decryption/checksum inspection.
plain=command(['age','--decrypt','--identity','/dev/stdin',str(copy)],TARGET,key+b'\n').stdout
with zipfile.ZipFile(io.BytesIO(plain)) as z: entries={name:z.read(name) for name in z.namelist()}
meta=json.loads(entries['manifest.json']);meta['pg_major']='17';entries['manifest.json']=json.dumps(meta).encode()
output=io.BytesIO()
with zipfile.ZipFile(output,'w') as z:
    for name,data in entries.items():
        info=zipfile.ZipInfo(name);info.external_attr=(0o100600)<<16;z.writestr(info,data)
wrong_version=TARGET/'wrong-version.age';wrong_version.write_bytes(command(['age','--encrypt','--recipient',recipient],TARGET,output.getvalue()).stdout);wrong_version.chmod(0o600)
refused,_=cli(TARGET,{'operation':'inspect','path':str(wrong_version)},key)
assert not refused['can_apply'] and refused['facts']['database']=='incompatible'
# A corrupted encrypted body cannot be inspected or restored.
broken=TARGET/'broken.age';broken.write_bytes(package[:-10]+b'corruption');broken.chmod(0o600)
_,code=cli(TARGET,{'operation':'inspect','path':str(broken)},key,check=False);assert code==1
assert hashlib.sha256(unit.read_bytes()).hexdigest()==unit_hash
assert command(['systemctl','--user','show','myct-transmission.service','--property=ActiveState','--value'],BASE).stdout.strip()==b'inactive'
assert command(['systemctl','--user','show','myct-transmission.service','--property=UnitFileState','--value'],BASE).stdout.strip()==b'static'
sql(TARGET,"INSERT INTO public.myct_setup_migrations VALUES(2,'"+'0'*64+"');")
_,code=cli(TARGET,{'operation':'inspect','path':str(copy)},key,check=False);assert code==1
# Source PostgreSQL remains live and unchanged throughout logical export.
assert sql(SOURCE,'SELECT count(*) FROM public.execution_events;').strip()==b'2'
print(json.dumps({'environment':'rootless Fedora 44 x86_64; two isolated HOME/PG clusters','postgres':command(['pg_dump','--version'],SOURCE).stdout.decode().strip(),
 'age':command(['age','--version'],SOURCE).stdout.decode().strip(),'basic_excludes_credentials':True,'private_encrypted':True,'real_pg_dump':True,'validated_event_restore':True,
 'source_db_not_stopped':True,'existing_user_service_preserved':True,'no_service_enable_or_start':True,'source_credentials_unchanged':True,'inspect_is_read_only':True,'home_mapping':True,'catalogs_historical_only':True,'no_oauth_credentials_restored':True,
 'repeat_unchanged':True,'existing_data_conflict_blocked':True,'missing_identity_and_tampering_blocked':True,'postgres_major_mismatch_blocked':True,'future_migration_marker_blocked':True,'physical_fedora_clean':'NOT VALIDATED','moto_setup4':'NOT VALIDATED','personal_data':'NOT ACCESSED'}))
