"""SETUP-4 bounded recovery packages. Data is untrusted; never execute supplied SQL,
units or commands. Only migration-001 events can enter an empty managed database.
"""
import base64
import io
import stat
import resource
from datetime import datetime, timezone

REC_MAX=32*1024*1024
REC_ITEM=16*1024*1024
REC_COUNT=256
REC_EXCLUDES=['rclone.conf/OAuth/Google credentials','SSH private keys and host keys',
 'Prowlarr API keys/passwords/indexers','Transmission RPC credentials/session/seeding',
 'GNOME Keyring/Narys/application tokens','media/hardlinks/download data',
 'unselected catalogs','running job state','PGDATA/other databases/roles',
 'arbitrary files and systemd unit contents']


def rec_bytes(path,limit=REC_ITEM):
    path=Path(path)
    if path.resolve()!=path:
        raise SetupError('Caminho redirecionado recusado; preservar origem/destino.')
    fd=open_user_file(path,os.O_RDONLY)
    with os.fdopen(fd,'rb') as file:
        info=os.fstat(file.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_nlink!=1 or info.st_size>limit:
            raise SetupError('Arquivo inválido, não pertencente à conta ou acima do limite.')
        data=file.read(limit+1)
        if len(data)>limit: raise SetupError('Arquivo acima do limite.')
        return data


def rec_path(value):
    if not isinstance(value,str) or len(value)>1024 or not value.startswith('/') or any(ord(c)<32 for c in value):
        raise SetupError('Informe caminho absoluto privado.')
    path=Path(value)
    if path.resolve()!=path or '..' in path.parts or Path.home() not in path.parents:
        raise SetupError('Destino deve estar dentro do HOME atual, sem links/traversal.')
    if any((parent/'.git').exists() or (parent/'.git').is_symlink() for parent in path.parents):
        raise SetupError('Destino dentro de um repositório Git recusado.')
    repo=Path.home()/'Projetos/My-CT'
    if path==repo or repo in path.parents:
        raise SetupError('Pacotes e recuperação devem ficar fora do repositório.')
    return path


def rec_private_parent(path, allow_missing=False):
    parent=path.parent
    if allow_missing and not parent.exists() and cloud_guard(parent): return
    if not parent.is_dir() or not cloud_guard(parent) or parent.stat().st_mode & 0o077:
        raise SetupError('Crie/selecione diretório privado 0700, pertencente à conta, fora do Git.')


def rec_bounded(command,input=None,limit=REC_MAX,timeout=90,env=None):
    """Bound output before reading. stderr never leaves memory or appears in JSON/logs."""
    import threading
    def limits():
        resource.setrlimit(resource.RLIMIT_CORE,(0,0))
        resource.setrlimit(resource.RLIMIT_CPU,(60,60))
        if Path(command[0]).name in ('pg_dump','pg_restore','psql'):
            resource.setrlimit(resource.RLIMIT_AS,(384*1024*1024,384*1024*1024))
    try:
        process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=env,preexec_fn=limits)
    except OSError:
        raise SetupError(Path(command[0]).name+': ferramenta indisponível; nenhuma operação confirmada.') from None
    timer=threading.Timer(timeout,process.kill);timer.start()
    def feed():
        try: process.stdin.write(input or b'');process.stdin.close()
        except (BrokenPipeError,OSError): pass
        finally:
            try: process.stdin.close()
            except OSError: pass
    writer=threading.Thread(target=feed,daemon=True);writer.start()
    try:
        data=process.stdout.read(limit+1)
        if len(data)>limit:
            process.kill();raise SetupError('Saída acima do limite; operação não confirmada.')
        process.wait();writer.join(2)
        if process.returncode:
            raise SetupError(Path(command[0]).name+': falha ou prazo expirou; saída privada omitida. Confira componente/chave e tente nova inspeção.')
        return data
    finally:
        timer.cancel()
        if process.poll() is None: process.kill();process.wait()
        process.stdout.close()


def rec_db_owned():
    root,state=paths();user=pwd.getpwuid(os.getuid()).pw_name
    expected=json.dumps({'schema':1,'user':user,'root':str(root)},sort_keys=True)
    guarded=(root,root/'run',root/'data',root/'data/PG_VERSION',state/'owned.json')
    try:
        receipt=rec_bytes(state/'owned.json',4096).decode()
    except (OSError,SetupError,UnicodeError): return False
    return all(cloud_guard(path) for path in guarded) and receipt==expected and (root/'data/PG_VERSION').is_file()


def rec_sql(sql):
    if not rec_db_owned(): raise SetupError('PostgreSQL anterior/não gerenciado preservado. Prepare a Base em host novo; não há adoção automática.')
    root,_=paths();user=pwd.getpwuid(os.getuid()).pw_name
    env={k:v for k,v in os.environ.items() if not k.startswith('PG')}
    env.update(LC_ALL='C',PGCONNECT_TIMEOUT='3',PGOPTIONS='-c search_path=pg_catalog -c statement_timeout=15000 -c lock_timeout=3000')
    return rec_bounded(['psql','-X','-w','-A','-t','-h',str(root/'run'),'-p','5432','-U',user,'-d','myct','-v','ON_ERROR_STOP=1'],input=sql.encode(),env=env)


def rec_db_check():
    expected=MANIFEST['sha256']['server/migrations/001_execution_events.sql']
    sql="""SELECT json_build_object(
      'migration',(SELECT sha256 FROM public.myct_setup_migrations WHERE version=1),
      'tables',(SELECT string_agg(table_schema||'.'||table_name,',' ORDER BY table_schema,table_name) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema') AND table_type='BASE TABLE'),
      'migrations',(SELECT count(*) FROM public.myct_setup_migrations),
      'rules',(SELECT count(*) FROM pg_rewrite r JOIN pg_class c ON c.oid=r.ev_class JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'),
      'columns',(SELECT string_agg(column_name || ':' || data_type,',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name='execution_events'),
      'triggers',(SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal),
      'functions',(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'),
      'extensions',(SELECT count(*) FROM pg_extension WHERE extname<>'plpgsql'),
      'version',current_setting('server_version_num'),'directory',current_setting('data_directory'),'listen',current_setting('listen_addresses'),'owner',current_user);
"""
    try: result=json.loads(rec_sql(sql))
    except ValueError: raise SetupError('Contrato PostgreSQL não confirmado.') from None
    if (result.get('directory')!=str(paths()[0]/'data') or result.get('listen')!='' or result.get('owner')!=pwd.getpwuid(os.getuid()).pw_name or result.get('migration')!=expected or result.get('tables')!='public.execution_events,public.myct_setup_migrations' or result.get('migrations')!=1 or result.get('rules')!=0 or result.get('triggers')!=0 or result.get('functions')!=0 or result.get('extensions')!=0 or
        result.get('columns')!='id:bigint,occurred_at:timestamp with time zone,event_type:text'):
        raise SetupError('Banco fora da migração 001 exclusiva do My-CT; recuperação automática recusada.')
    return str(int(result['version'])//10000)


def rec_event_rows(dump):
    # pg_restore only renders text to stdout. That text is NEVER fed to psql.
    rendered=rec_bounded(['pg_restore','--data-only','--table=execution_events','--file=-'],input=dump).decode('utf-8')
    header='COPY public.execution_events (id, occurred_at, event_type) FROM stdin;\n'
    if rendered.count(header)!=1: raise SetupError('Dump sem o contrato COPY da migração 001.')
    body=rendered.split(header,1)[1]
    if '\n\\.\n' not in '\n'+body: raise SetupError('Dump incompleto.')
    raw=body.split('\\.\n',1)[0]
    rows=[];seen=set()
    for line in raw.splitlines():
        parts=line.split('\t')
        if len(parts)!=3 or not re.fullmatch('[1-9][0-9]{0,18}',parts[0]) or not re.fullmatch('[A-Za-z0-9_.-]{1,64}',parts[2]):
            raise SetupError('Dados do dump fora do contrato permitido.')
        identity=int(parts[0])
        if identity>=2**63-1 or identity in seen: raise SetupError('IDs inválidos/duplicados no dump.')
        try:
            stamp=datetime.fromisoformat(parts[1])
            if stamp.tzinfo is None: raise ValueError()
        except ValueError: raise SetupError('Data do dump incompatível; recuperação recusada.') from None
        seen.add(identity);rows.append([identity,stamp.astimezone(timezone.utc).isoformat(timespec='microseconds'),parts[2]])
        if len(rows)>10000: raise SetupError('Limite desta candidata: 10.000 eventos.')
    return sorted(rows)


def rec_current_rows():
    sql="SELECT coalesce(json_agg(t ORDER BY id),'[]') FROM (SELECT id,to_char(occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"+00:00\"') AS stamp,event_type FROM public.execution_events ORDER BY id) t;"
    try: data=json.loads(rec_sql(sql));return [[v['id'],v['stamp'],v['event_type']] for v in data]
    except (ValueError,KeyError,TypeError): raise SetupError('Dados atuais não confirmados; nada será substituído.') from None


def rec_basic():
    home=Path.home();user=pwd.getpwuid(os.getuid()).pw_name
    preferences={}
    transmission=home/'.config/myct-transmission/settings.json'
    if transmission.is_file():
        row=json.loads(rec_bytes(transmission,65536))
        row={k.replace('_','-'):v for k,v in row.items()}
        preferences['transmission']={k:row[k] for k in ('rpc-bind-address','rpc-port','download-dir') if k in row}
    prowlarr=home/'.local/share/myct/prowlarr/config.xml'
    if prowlarr.is_file():
        row=private_xml(prowlarr)
        preferences['prowlarr']={k:row.findtext(k,'') for k in ('BindAddress','Port','EnableSsl')}
    selection=cloud_selection_file()
    if selection.is_file(): preferences['rclone']=cloud_selection()
    services=[]
    for name,scope in (('myct-core','system'),('myct-postgres','user'),('myct-transmission','user'),('myct-prowlarr','user')):
        info=addon_service(name,scope=='user')
        services.append({'name':name,'scope':scope,'enabled':info['UnitFileState']=='enabled','present':info['LoadState']=='loaded'})
    owned={}
    for name,location in [('base',home/'.local/state/myct-setup/owned.json')]+[(n,home/'.local/state/myct-setup'/n/'owned.json') for n in ('transmission','prowlarr','rclone')]:
        owned[name]=location.is_file() # Observation only, never a transferable ownership receipt.
    versions={name:out(command).replace('\n',' ')[:120] for name,command in [('postgres',['postgres','--version']),('rclone',['rclone','version']),('transmission',['transmission-daemon','--version'])] if shutil.which(command[0])}
    versions['core']='unknown; rebuild via trusted SETUP-1'
    versions['prowlarr']='unknown offline; manifest '+MANIFEST['addons']['prowlarr']['version']
    result={'schema':1,'release':MANIFEST['release'],'manifest_sha256':digest(MANIFEST),
            'origin':{'home':str(home),'user':user,'arch':platform.machine(),'fedora':dict(line.split('=',1) for line in read('/etc/os-release').splitlines() if '=' in line).get('VERSION_ID','').strip('"')},
            'services':services,'setup_receipts_present':owned,'versions':versions,'preferences':preferences}
    rec_validate_basic(result)
    return result


def rec_validate_basic(value):
    if not isinstance(value,dict) or set(value)!={'schema','release','manifest_sha256','origin','services','setup_receipts_present','versions','preferences'} or type(value['schema'])!=int or value['schema']!=1:
        raise SetupError('Inventário básico inválido.')
    origin=value['origin']
    if not isinstance(origin,dict) or set(origin)!={'home','user','arch','fedora'} or not re.fullmatch('/[A-Za-z0-9_./-]+',origin['home']) or '..' in Path(origin['home']).parts or not re.fullmatch('[a-z_][a-z0-9_-]*\\$?',origin['user']):
        raise SetupError('Origem incompatível; revisar remapeamento manualmente.')
    if not isinstance(value['release'],str) or not re.fullmatch('myct-setup-(?:[1-4]-rc1|4-fix1-rc1)',value['release']) or not re.fullmatch('[a-f0-9]{64}',value['manifest_sha256']): raise SetupError('Versão do inventário desconhecida.')
    if origin['arch'] not in MANIFEST['architectures'] or origin['fedora'] not in ('43','44'): raise SetupError('Origem Fedora/arquitetura incompatível.')
    receipts=value['setup_receipts_present']
    if not isinstance(receipts,dict) or set(receipts)!={'base','transmission','prowlarr','rclone'} or any(type(v)!=bool for v in receipts.values()): raise SetupError('Inventário SETUP inválido.')
    versions=value['versions']
    if not isinstance(versions,dict) or set(versions)-{'postgres','rclone','transmission','core','prowlarr'} or any(not isinstance(v,str) or len(v)>200 or any(ord(c)<32 for c in v) for v in versions.values()): raise SetupError('Versões fora do contrato.')
    prefs=value['preferences']
    if not isinstance(prefs,dict) or set(prefs)-{'transmission','prowlarr','rclone'}: raise SetupError('Preferências desconhecidas recusadas.')
    if 'transmission' in prefs:
        row=prefs['transmission']
        if set(row)-{'rpc-bind-address','rpc-port','download-dir'} or row.get('rpc-bind-address','127.0.0.1')!='127.0.0.1' or row.get('rpc-port',19091)!=19091:
            raise SetupError('Preferência Transmission incompatível.')
        path=row.get('download-dir',origin['home']+'/Downloads/My-CT/Torrents')
        if not isinstance(path,str) or not path.startswith(origin['home']+'/') or '..' in Path(path).parts or len(path)>1024 or any(ord(c)<32 for c in path): raise SetupError('Caminho Transmission requer intervenção.')
    if 'prowlarr' in prefs:
        row=prefs['prowlarr']
        if set(row)-{'BindAddress','Port','EnableSsl'} or row.get('BindAddress','127.0.0.1')!='127.0.0.1' or row.get('Port','9696')!='9696' or row.get('EnableSsl','False').lower()!='false':
            raise SetupError('Preferência Prowlarr incompatível.')
    if 'rclone' in prefs: cloud_validate_selection(**prefs['rclone'])
    # Versions/services are informational, never executed or installed from this data.
    if not isinstance(value['services'],list) or len(value['services'])!=4 or len({row.get('name') for row in value['services']})!=4:
        raise SetupError('Inventário de serviços inválido.')
    for row in value['services']:
        if set(row)!={'name','scope','enabled','present'} or row['name'] not in ('myct-core','myct-postgres','myct-transmission','myct-prowlarr') or row['scope'] not in ('user','system') or type(row['enabled'])!=bool or type(row['present'])!=bool:
            raise SetupError('Serviço fora do contrato; nenhum arquivo de unidade será importado.')


def rec_inventory():
    home=Path.home()
    return {'schema':1,'operation':'inventory','release':MANIFEST['release'],
      'categories':[{'id':'basic','available':True,'private':False,'description':'Versões, estado desejado de serviços e preferências allowlist sem segredos; não inclui unidades executáveis.'},
      {'id':'database','available':rec_db_owned(),'private':True,'description':'Dump lógico exclusivo myct/migração 001. Nunca PGDATA. Legados bloqueados; nenhuma consulta SQL neste inventário.'},
      {'id':'catalogs','available':any((home/'.local/share/myct'/n/'catalog.json').is_file() for n in ('archive','archive-torrents')),'private':True,'description':'Catálogos e pequenos manifestos/torrents selecionados: dados pessoais. Recuperação histórica; ativação automática pendente.'}],
      'age':bool(shutil.which('age')),'exclusions':REC_EXCLUDES,'limits':{'bytes':REC_MAX,'items':REC_COUNT,'events':10000},
      'message':'Inventário passivo. Nenhum dump, transferência, restauração ou acesso Drive foi iniciado.'}


def rec_catalogs():
    result={};home=Path.home()
    def add(name,data):
        if sum(map(len,result.values()))+len(data)>REC_MAX-65536:
            raise SetupError('Metadados selecionados acima do limite total de 32 MiB.')
        result[name]=data
    for directory,alias in (('archive','files'),('archive-torrents','torrents')):
        root=home/'.local/share/myct'/directory
        path=root/'catalog.json'
        if not path.exists(): continue
        lock=root/'catalog.lock'
        if not lock.is_file(): raise SetupError('Catálogo sem lock existente; revisão manual necessária.')
        fd=os.open(lock,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        with os.fdopen(fd,'rb') as handle:
            info=os.fstat(handle.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_nlink!=1:
                raise SetupError('Lock de catálogo inválido; nada foi modificado.')
            try: fcntl.flock(handle,fcntl.LOCK_SH|fcntl.LOCK_NB)
            except BlockingIOError: raise SetupError('Catálogo em uso; aguarde concluir o trabalho. Nada será interrompido.') from None
            add('catalogs/'+alias+'/catalog.json',rec_bytes(path))
            # Only selected small recovery metadata, never data trees/media.
            if alias=='torrents':
                for bundle in sorted((root/'bundles').glob('*')) if (root/'bundles').is_dir() else []:
                    if not re.fullmatch('[a-f0-9]{64}',bundle.name): raise SetupError('Bundle de catálogo desconhecido; revisão necessária.')
                    for filename in ('manifest.json','original.torrent'):
                        source=bundle/filename
                        if source.exists(): add('catalogs/torrents/bundles/'+bundle.name+'/'+filename,rec_bytes(source,4*1024*1024))
                    if len(result)>REC_COUNT-4: raise SetupError('Muitos metadados; limite desta candidata excedido.')
    if not result: raise SetupError('Nenhum catálogo selecionado disponível.')
    return result


def rec_options(request):
    for key in ('database','catalogs'):
        if type(request.get(key,False))!=bool: raise SetupError('Seleção de categorias inválida.')
    private=request.get('database',False) or request.get('catalogs',False)
    recipient=request.get('recipient','')
    if not isinstance(recipient,str) or (recipient and not re.fullmatch('age1[0-9a-z]{58}',recipient)):
        raise SetupError('Informe somente destinatário PÚBLICO age1; nunca a identidade secreta neste campo.')
    if private and (not shutil.which('age') or not recipient):
        raise SetupError('Dados privados exigem age disponível e destinatário público age1 válido. Não informe a chave secreta aqui.')
    return bool(private),recipient


def rec_environment():
    info=dict(line.split('=',1) for line in read('/etc/os-release').splitlines() if '=' in line)
    if info.get('ID','').strip('"')!='fedora' or info.get('VERSION_ID','').strip('"') not in ('43','44') or Path('/run/ostree-booted').exists() or os.getuid()==0:
        raise SetupError('Recuperação requer Fedora tradicional 43/44 e conta SSH normal.')

def rec_export_plan(request):
    rec_environment()
    private,recipient=rec_options(request)
    destination=rec_path(request['path']);rec_private_parent(destination,allow_missing=True)
    if destination.exists() or destination.is_symlink(): raise SetupError('Destino já existe; nenhuma sobrescrita de backup.')
    basic=rec_basic()
    catalogs=rec_catalogs() if request.get('catalogs') else {}
    if request.get('database') and (not rec_db_owned() or not shutil.which('pg_dump') or not shutil.which('pg_restore')):
        raise SetupError('Dump requer Base gerenciada, pg_dump e pg_restore; banco anterior não será acessado/adotado.')
    facts={'basic':basic,'catalogs':{k:hashlib.sha256(v).hexdigest() for k,v in catalogs.items()},
           'path':str(destination),'private':private,'recipient':recipient,'database':request.get('database',False),
           'pg_major':read(paths()[0]/'data/PG_VERSION').strip() if request.get('database') else '',
           'migration':MANIFEST['sha256']['server/migrations/001_execution_events.sql']}
    return {'schema':1,'operation':'plan-export','plan_id':digest(facts),'facts':facts,'can_apply':True,
      'summary':['Destino privado: '+str(destination),'Categorias: básico'+(', banco privado' if request.get('database') else '')+(', catálogos privados' if request.get('catalogs') else ''),
        'Criptografia age obrigatória para banco/catálogos.' if private else 'Básico sem segredos, privado 0600; checksums não autenticam a origem.',
        'Sem serviços, privilégios administrativos, uploads ou exclusões. Dump consistente sem parar PostgreSQL.' if request.get('database') else 'Sem SQL, serviços, privilégios administrativos, uploads ou exclusões.'],
      'exclusions':REC_EXCLUDES}


def rec_publish(path,data):
    with user_parent(path,create=True): pass
    rec_private_parent(path)
    if not cloud_guard(path) or path.exists() or path.is_symlink(): raise SetupError('Destino existente/redirecionado preservado.')
    atomic_user_write(path,data)


def rec_export(request):
    plan=rec_export_plan(request)
    if request.get('approved')!=plan['plan_id']: raise SetupError('Plano de exportação ausente ou alterado; nada exportado.')
    facts=plan['facts'];files={'basic.json':json.dumps(facts['basic'],sort_keys=True).encode()}
    if request.get('catalogs'): files.update(rec_catalogs())
    if request.get('database'):
        major=rec_db_check()
        if major!=facts['pg_major']: raise SetupError('Versão PostgreSQL divergente.')
        root,_=paths();user=pwd.getpwuid(os.getuid()).pw_name
        env={k:v for k,v in os.environ.items() if not k.startswith('PG')};env.update(LC_ALL='C',PGCONNECT_TIMEOUT='3')
        files['database.dump']=rec_bounded(['pg_dump','-Fc','-w','--no-owner','--no-privileges','--table=public.execution_events','--table=public.myct_setup_migrations','-h',str(root/'run'),'-p','5432','-U',user,'-d','myct'],env=env,limit=REC_ITEM)
        rec_event_rows(files['database.dump']) # Do not publish an unusable/incomplete logical dump.
    if request.get('catalogs') and {k:hashlib.sha256(v).hexdigest() for k,v in files.items() if k.startswith('catalogs/')}!=facts['catalogs']:
        raise SetupError('Catálogos mudaram desde a prévia; refaça o plano.')
    meta={'schema':1,'release':MANIFEST['release'],'created':datetime.now(timezone.utc).isoformat(),
      'private':facts['private'],'origin':facts['basic']['origin'],'pg_major':facts['pg_major'],
      'migration':facts['migration'],'exclusions':REC_EXCLUDES,
      'items':{k:{'size':len(v),'sha256':hashlib.sha256(v).hexdigest()} for k,v in files.items()}}
    if len(files)>REC_COUNT-1 or sum(map(len,files.values()))>REC_MAX-65536: raise SetupError('Pacote excede limites da candidata.')
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,'w',zipfile.ZIP_STORED) as archive:
        for name,data in {'manifest.json':json.dumps(meta,sort_keys=True).encode(),**files}.items():
            info=zipfile.ZipInfo(name);info.external_attr=(stat.S_IFREG|0o600)<<16;archive.writestr(info,data)
    data=buffer.getvalue()
    if facts['private']: data=rec_bounded(['age','--encrypt','--recipient',facts['recipient']],input=data,limit=REC_MAX+65536)
    # Verify the ZIP before publication; encrypted transport is produced by age.
    rec_unpack(buffer.getvalue(),encrypted=facts['private'])
    rec_publish(Path(facts['path']),data)
    return {'schema':1,'operation':'export','state':'complete','path':facts['path'],'sha256':hashlib.sha256(data).hexdigest(),
            'items':len(files),'encrypted':facts['private'],'message':'Pacote completo publicado atomicamente. Copie pela seção Arquivos/SFTP; nenhuma transferência automática.'}


def rec_member(name):
    return name in ('basic.json','database.dump') or bool(re.fullmatch(r'catalogs/(files|torrents)/catalog\.json',name)) or bool(re.fullmatch(r'catalogs/torrents/bundles/[a-f0-9]{64}/(manifest\.json|original\.torrent)',name))


def rec_unpack(raw,encrypted=False):
    if len(raw)>REC_MAX: raise SetupError('Pacote acima do limite.')
    files={}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries=archive.infolist()
        if not 2<=len(entries)<=REC_COUNT or len({i.filename for i in entries})!=len(entries): raise SetupError('Quantidade/duplicação inválida de itens.')
        total=0
        for info in entries:
            if info.filename!='manifest.json' and not rec_member(info.filename): raise SetupError('Nome/caminho de item recusado; nenhum arquivo extraído.')
            mode=info.external_attr>>16
            if info.compress_type!=zipfile.ZIP_STORED or not stat.S_ISREG(mode) or info.file_size>REC_ITEM or info.flag_bits&1:
                raise SetupError('Compressão, link, dispositivo ou tamanho incompatível.')
            total+=info.file_size
            if total>REC_MAX: raise SetupError('Expansão do pacote excede limite.')
            files[info.filename]=archive.read(info)
    try:
        meta=json.loads(files.pop('manifest.json'))
        if set(meta)!={'schema','release','created','private','origin','pg_major','migration','exclusions','items'} or type(meta['schema'])!=int or meta['schema']!=1 or type(meta['private'])!=bool or not isinstance(meta['items'],dict): raise ValueError()
        if meta['private'] and not encrypted: raise SetupError('Pacote privado sem criptografia recusado.')
        if any(k!='basic.json' for k in files) and not (encrypted and meta['private']): raise SetupError('Dados privados exigem pacote age.')
        if meta['exclusions']!=REC_EXCLUDES or not isinstance(meta['pg_major'],str) or (meta['pg_major'] and not re.fullmatch('[0-9]{2}',meta['pg_major'])): raise ValueError()
        if ('database.dump' in files)!=bool(meta['pg_major']): raise ValueError()
        if set(meta['items'])!=set(files): raise ValueError()
        for name,data in files.items():
            if meta['items'][name]!={'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}: raise ValueError()
        basic=json.loads(files['basic.json']);rec_validate_basic(basic)
        if meta['origin']!=basic['origin'] or meta['migration']!=MANIFEST['sha256']['server/migrations/001_execution_events.sql']:
            raise SetupError('Origem/migração incompatível com esta candidata.')
        if not re.fullmatch('myct-setup-(?:[1-4]-rc1|4-fix1-rc1)',meta['release']): raise SetupError('Versão de pacote desconhecida.')
    except (ValueError,KeyError,TypeError): raise SetupError('Manifesto, conteúdo ou SHA-256 inválido; pacote recusado.') from None
    return meta,files,basic


def rec_open(request,identity):
    path=rec_path(request['path']);raw=rec_bytes(path,REC_MAX+65536)
    encrypted=raw.startswith(b'age-encryption.org/v1\n')
    package_sha=hashlib.sha256(raw).hexdigest()
    if encrypted:
        if not shutil.which('age') or not re.fullmatch(rb'AGE-SECRET-KEY-1[0-9A-Z]{58}',bytes(identity)):
            raise SetupError('Pacote age exige chave secreta fornecida privadamente nesta operação, sem salvar.')
        raw=rec_bounded(['age','--decrypt','--identity','/dev/stdin',str(path)],input=bytes(identity)+b'\n',limit=REC_MAX)
        # Refuse path races between hash and decryption.
        if hashlib.sha256(rec_bytes(path,REC_MAX+65536)).hexdigest()!=package_sha: raise SetupError('Pacote mudou durante inspeção.')
    meta,files,basic=rec_unpack(raw,encrypted)
    return meta,files,basic,package_sha


def rec_restore_plan(request,identity,ignore_lock=False):
    rec_environment()
    meta,files,basic,package_sha=rec_open(request,identity)
    identity_id=digest(meta)
    root=Path.home()/'.local/share/myct/recovery'/identity_id
    mappings={'old_home':basic['origin']['home'],'new_home':str(Path.home()),'old_user':basic['origin']['user'],'new_user':pwd.getpwuid(os.getuid()).pw_name,
              'media_root':str(rec_path(request.get('media_root') or str(Path.home()/'Downloads/My-CT/Torrents')))}
    mapping_bytes=json.dumps(mappings,sort_keys=True).encode()
    destinations={root/'basic.json':files['basic.json'],root/'mapping.json':mapping_bytes}
    for name,data in files.items():
        if name.startswith('catalogs/'): destinations[root/name]=data
    cloud=basic['preferences'].get('rclone')
    cloud_preview=None
    warnings=['Unidades não são importadas. Reconstrua Base/Transmission/Prowlarr/Rclone pelos alvos SETUP-1/2/3, após aprovação própria.',
              'Preferências Transmission/Prowlarr recuperadas como receita; aplicação automática pendente. Nenhuma configuração ativa será substituída.',
              'Catálogos recuperados são históricos, sem ativação no motor. Caminhos originais preservados; remapeamento separado.',
              'Mídias, dados de seeding e objetos Drive não foram incluídos/verificados. OAuth, API keys e credenciais exigem configuração humana.']
    if cloud:
        mapped=dict(cloud)
        current=None
        if cloud_selection_file().exists():
            try: current=cloud_selection()
            except CloudConfigError: pass # The destination comparison below blocks invalid/different files.
        old=mappings['old_home']
        if mapped['config'].startswith(old+'/'): mapped['config']=str(Path.home())+mapped['config'][len(old):]
        elif mapped['config']:
            warnings.append('Caminho Rclone externo ao HOME antigo exige seleção humana; seleção não aplicada.')
            mapped=None
        if mapped and not cloud_selection_file().exists() and any((Path.home()/'.local/share/myct'/name).exists() for name in ('archive','archive-torrents','cloud-jobs')):
            warnings.append('Estado de nuvem anterior detectado: seleção Rclone não aplicada; revisão humana necessária antes de afetar trabalhos existentes.')
            mapped=None
        if mapped:
            cloud_validate_selection(**mapped)
            destinations[cloud_selection_file()]=(json.dumps(mapped,sort_keys=True)+'\n').encode()
        exists=cloud_selection_file().exists() or cloud_selection_file().is_symlink()
        same=bool(mapped and user_path_safe(cloud_selection_file()) and read(cloud_selection_file())==destinations[cloud_selection_file()].decode())
        status='historical' if mapped is None else 'existing' if same else 'conflict' if exists else 'apply'
        cloud_preview={'source':cloud,'result':mapped,'active_before':current,'status':status,'will_apply':status=='apply'}
    blockers=[];rows=[]
    inspect_setup_lock(blockers,ignore_lock)
    for path,data in destinations.items():
        rec_path(str(path))
        if not cloud_guard(path): blockers.append('Caminho de destino redirecionado ou com permissão/dono divergentes: '+str(path));state='conflict'
        elif path.exists() and path.stat().st_mode & 0o077:
            state='conflict';blockers.append('Arquivo de destino sem permissão privada; preservado: '+str(path))
        elif path.parent.exists() and path.parent.stat().st_mode & 0o077:
            state='conflict';blockers.append('Diretório de destino existente não privado; ajuste humano necessário: '+str(path.parent))
        elif path.exists():
            state='existing' if rec_bytes(path)==data else 'conflict'
            if state=='conflict': blockers.append('Arquivo anterior divergente preservado: '+str(path))
        else: state='missing'
        rows.append({'path':str(path),'state':state,'sha256':hashlib.sha256(data).hexdigest()})
    db='not_included';events=None;target_major=''
    if 'database.dump' in files:
        if not rec_db_owned(): blockers.append('Cluster/banco anterior ou ausente: prepare Base gerenciada em host novo; não substituir/adotar.');db='intervention'
        else:
            major=rec_db_check();target_major=major
            if major!=meta['pg_major']: blockers.append('Versão PostgreSQL diferente; migração entre versões requer procedimento humano.');db='incompatible'
            else:
                events=rec_event_rows(files['database.dump']);current=rec_current_rows()
                db='existing' if current==events else 'empty' if not current else 'conflict'
                if db=='conflict': blockers.append('Banco My-CT contém dados diferentes; nenhuma substituição permitida.')
    comparison=[]
    for service in basic['services']:
        target=addon_service(service['name'],service['scope']=='user')
        comparison.append({'name':service['name'],'source_present':service['present'],'target_present':target['LoadState']=='loaded','desired_boot':service['enabled'],'target_boot':target['UnitFileState']=='enabled'})
    facts={'source_pg_major':meta['pg_major'],'target_pg_major':target_major,'components':comparison,'package_sha256':package_sha,'manifest_id':identity_id,'mapping':mappings,'destinations':rows,'database':db,'cloud_selection':cloud_preview,
           'events_sha256':digest(events) if events is not None else '', 'release':MANIFEST['release']}
    return {'schema':1,'operation':'inspect','plan_id':digest(facts),'facts':facts,'can_apply':not blockers,
       'blockers':blockers,'warnings':warnings,'summary':['Origem: '+mappings['old_user']+' · '+mappings['old_home'],'Destino: '+mappings['new_user']+' · '+mappings['new_home'],
       'Pacote: '+meta['release']+' · schema 1 · migração 001','Itens íntegros: '+str(len(files)),'Banco: '+db+' · PostgreSQL origem '+(meta['pg_major'] or 'não incluído')+' / destino '+(target_major or 'não consultado'),'Nenhum serviço será criado/habilitado ou operação administrativa executada pela restauração.'],
       'services':basic['services'],'exclusions':meta['exclusions']},destinations,events


def rec_restore(request,identity):
    plan,destinations,events=rec_restore_plan(request,identity)
    if request.get('approved')!=plan['plan_id'] or not plan['can_apply']: raise SetupError('Prévia ausente, alterada ou com conflitos; nada restaurado.')
    completed=[]
    with setup_lock_file() as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise SetupError('Outra instalação/recuperação em execução.') from None
        fresh,destinations,events=rec_restore_plan(request,identity,ignore_lock=True)
        if fresh['plan_id']!=plan['plan_id'] or not fresh['can_apply']: raise SetupError('Estado mudou sob lock; refaça a inspeção.')
        for path,data in destinations.items():
            if path.exists(): completed.append('existing');continue
            path.parent.mkdir(parents=True,mode=0o700,exist_ok=True)
            rec_publish(path,data);completed.append('restored')
        if plan['facts']['database']=='empty':
            values=','.join(f"({row[0]},'{row[1]}','{row[2]}')" for row in events)
            sql="BEGIN; LOCK TABLE public.execution_events IN ACCESS EXCLUSIVE MODE; DO $$ BEGIN IF EXISTS (SELECT 1 FROM public.execution_events) THEN RAISE EXCEPTION 'destination not empty'; END IF; END $$;\n"
            if values:
                sql+='INSERT INTO public.execution_events (id,occurred_at,event_type) OVERRIDING SYSTEM VALUE VALUES '+values+';\n'
                sql+="SELECT pg_catalog.setval(pg_get_serial_sequence('public.execution_events','id'),"+str(max(row[0] for row in events))+");\n"
            rec_sql(sql+'COMMIT;')
            if rec_current_rows()!=events: raise SetupError('Dados PostgreSQL sem confirmação; inspecione antes de tentar novamente.')
            completed.append('database_restored')
    return {'schema':1,'operation':'restore','state':'complete','path':str(Path.home()/'.local/share/myct/recovery'/plan['facts']['manifest_id']),
      'completed':completed,'warnings':plan['warnings'],'pending':['Aplicação de receitas Transmission/Prowlarr','Ativação/remapeamento de catálogos históricos','Reconstrução de serviços quando necessária pelos alvos SETUP-1/2/3','OAuth/API keys e mídia externa não incluídos'],'message':'Dados selecionados recuperados e verificados. Receitas/catálogos históricos não ativam serviços, trabalhos ou recuperação de mídias.'}


def backup_main(action='backup'):
    if action=='diagnose':
        emit(rec_inventory());return
    if action!='backup':
        raise SetupError('Operação reservada ao fluxo Backup; nenhuma alteração executada.')
    request_raw=sys.stdin.buffer.readline(16385)
    if len(request_raw)>16384: raise SetupError('Pedido acima do limite.')
    identity=bytearray(sys.stdin.buffer.readline(257).strip())
    try:
        request=json.loads(request_raw) if request_raw else {'operation':'inventory'}
        operation=request.get('operation')
        if action not in ('backup','diagnose') or (action=='diagnose' and operation!='inventory'):
            raise SetupError('Diagnóstico permite exclusivamente inventário passivo; exportação/restauração exigem o fluxo Backup e aprovação própria.')
        if operation=='inventory': result=rec_inventory()
        elif operation=='plan-export': result=rec_export_plan(request)
        elif operation=='export': result=rec_export(request)
        elif operation=='inspect': result=rec_restore_plan(request,identity)[0]
        elif operation=='restore': result=rec_restore(request,identity)
        else: raise SetupError('Operação de recuperação desconhecida.')
        emit(result)
    except SetupError:
        raise
    except Exception:
        raise SetupError('Pacote/pedido incompatível ou operação interrompida. Nenhum sucesso presumido; reinspecione para retomar. Saída privada omitida.') from None
    finally: identity[:]=b'\0'*len(identity)
