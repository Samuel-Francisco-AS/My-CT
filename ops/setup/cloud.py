"""SETUP-3: passive inventory, fixed supervised provisioning, explicit online probe.
Never invokes archive engines or reads their catalogs/jobs during diagnosis.
"""
import configparser
import stat


def cloud_paths():
    return Path.home()/'.local/state/myct-setup/rclone', Path.home()/'.local/bin'


def cloud_local_command(args, timeout=10):
    # These are exclusively local version/config-path queries, or the two explicit
    # online probes below. Never inherit backend overrides, logging or debug flags.
    env = {k:v for k,v in os.environ.items() if not k.startswith('RCLONE_')}
    try:
        return subprocess.run(['rclone', *args], stdin=subprocess.DEVNULL,
                              capture_output=True, timeout=timeout, env=env)
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(args,124,b'',b'')


def cloud_config_path(selection):
    if selection['config']:
        return Path(selection['config'])
    if shutil.which('rclone'):
        reply=cloud_local_command(['config','file'])
        lines=reply.stdout.decode(errors='replace').strip().splitlines()
        if reply.returncode == 0 and lines and lines[-1].startswith('/'):
            path=lines[-1]
            cloud_validate_selection(selection['remote'],path)
            return Path(path)
        raise CloudConfigError('Rclone não informou o caminho local; selecione-o explicitamente.')
    base=os.environ.get('XDG_CONFIG_HOME') or str(Path.home()/'.config')
    old=Path.home()/'.rclone.conf'
    return old if old.exists() else Path(base)/'rclone/rclone.conf'


def cloud_config_metadata(selection):
    result={'state':'pending','configured':False,'path':'','stamp':None}
    try:
        path=cloud_config_path(selection)
        result['path']=str(path)
        if not path.exists() and not path.is_symlink():
            return result
        info=path.lstat()
        result['stamp']=[info.st_size,info.st_mtime_ns,info.st_ino]
        if (path.resolve()!=path or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid()
                or info.st_mode & 0o077 or info.st_nlink!=1 or info.st_size>1024*1024):
            result['state']='protected'; return result
        # No config dump/listremotes process; extract only selected backend type.
        parser=configparser.RawConfigParser(strict=True)
        with path.open() as stream:
            parser.read_file(stream)
        name=selection['remote'][:-1]
        if parser.has_section(name):
            result['configured']=parser.get(name,'type',fallback='').strip()=='drive'
            result['state']='configured' if result['configured'] else 'incompatible'
    except (OSError, ValueError, configparser.Error):
        result['state']='protected'
    return result


def cloud_guard(path):
    for candidate in (path,*path.parents):
        if candidate == Path.home().parent:
            break
        if candidate.is_symlink() or (candidate.exists() and (candidate.stat().st_uid!=os.getuid() or candidate.stat().st_mode & 0o022)):
            return False
    return True


def cloud_diagnose(selection=None, ignore_lock=False):
    selection_error=False
    if selection is None:
        try: selection=cloud_selection()
        except CloudConfigError:
            selection={'remote':'myct-gdrive:','config':''};selection_error=True
    selection=cloud_validate_selection(**selection)
    state,binpath=cloud_paths()
    user=pwd.getpwuid(os.getuid()).pw_name
    osinfo={k:v.strip('"') for k,v in (line.split('=',1) for line in read('/etc/os-release').splitlines() if '=' in line)}
    blockers=[]
    if selection_error:
        blockers.append('intervention: Seleção persistente protegida ou inválida; preservada, sem adoção.')
    if osinfo.get('ID')!='fedora' or osinfo.get('VERSION_ID') not in ('43','44') or Path('/run/ostree-booted').exists():
        blockers.append('incompatible: Requer Fedora DNF tradicional 43/44.')
    if platform.machine() not in MANIFEST['architectures'] or os.getuid()==0:
        blockers.append('incompatible: Requer conta SSH normal e arquitetura x86_64/aarch64.')
    installed=bool(shutil.which('rclone'))
    reply=cloud_local_command(['version']) if installed else None
    match=re.match(rb'rclone v(\d+)\.(\d+)\.(\d+)',reply.stdout if reply else b'')
    version='.'.join(part.decode() for part in match.groups()) if match else ''
    compatible=bool(match and tuple(map(int,match.groups())) >= tuple(map(int,MANIFEST['cloud']['version_min'].split('.'))))
    if installed and not compatible:
        blockers.append('incompatible: Rclone existente sem versão compatível confirmada (mínimo 1.70.0); não reinstalar.')
    config=cloud_config_metadata(selection)
    if not selection['config'] and config['path']:
        selection={**selection,'config':config['path']}
    helpers={}
    for name in MANIFEST['cloud']['helpers']:
        path=binpath/name; exists=path.exists() or path.is_symlink()
        valid=(exists and cloud_guard(path) and path.is_file() and path.stat().st_size<1024*1024
               and hashlib.sha256(path.read_bytes()).hexdigest()==MANIFEST['sha256']['ops/'+name]
               and (name.endswith('.py') or os.access(path,os.X_OK)))
        helpers[name]='compatible' if valid else 'previous' if exists else 'missing'
    if 'previous' in helpers.values():
        blockers.append('intervention: Helpers anteriores/divergentes preservados; sem adoção ou atualização automática. A inspeção continua disponível.')
    selected=cloud_selection_file()
    desired=json.dumps(selection,sort_keys=True)+'\n'
    selection_exists=selected.exists() or selected.is_symlink()
    selection_ok=selection_exists and cloud_guard(selected) and read(selected)==desired
    if selection_exists and (selected.is_symlink() or not selected.is_file() or selected.stat().st_mode & 0o077 or selected.stat().st_nlink != 1):
        blockers.append('intervention: Seleção persistente sem permissão privada; preservada.')
    if selection_exists and not selection_ok:
        blockers.append('intervention: Seleção persistente anterior preservada; alterações exigem revisão manual.')
    for path in (state,state.parent/'lock',state/'files.json',state/'owned.json',state/'events.jsonl',selected,binpath):
        if not cloud_guard(path):
            blockers.append('intervention: Caminho privado redirecionado ou com proprietário divergente; preservado.')
            break
    if not ignore_lock and (state.parent/'lock').is_file() and cloud_guard(state.parent/'lock'):
        with (state.parent/'lock').open() as lock:
            try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError: blockers.append('intervention: Outra instalação em execução; aguarde.')
    missing_tools=[tool for tool in ('python3','systemctl','systemd-run','loginctl') if not shutil.which(tool)]
    manager=ok(['systemctl','--user','show','--property=Version']) if not missing_tools else False
    linger=out(['loginctl','show-user',user,'--property=Linger','--value'])=='yes'
    if missing_tools or not manager:
        blockers.append('intervention: Requer python3 e systemd de usuário acessível por SSH/PAM; revisar ferramentas ausentes.')
    missing=[] if installed else ['rclone']
    if (missing or not linger) and (not shutil.which('sudo') or not shutil.which('dnf')):
        blockers.append('intervention: DNF e sudo necessários às etapas administrativas ausentes.')
    helpers_ok=all(value=='compatible' for value in helpers.values()) and selection_ok
    managed=read(state/'owned.json')==json.dumps({'schema':1,'component':'rclone','user':user},sort_keys=True)
    if (state/'owned.json').exists() and not managed:
        blockers.append('intervention: Recibo anterior divergente; preservado.')
    rows=[
      {'id':'packages','state':'existing' if installed else 'missing','privilege':'sudo',
       'description':'DNF install rclone somente se ausente; assinatura GPG obrigatória. Versão disponível resolvida pelos repositórios Fedora no momento da instalação (mínimo 1.70.0). Sem upgrade global.'},
      {'id':'rclone','state':'existing' if helpers_ok else 'unmanaged' if 'previous' in helpers.values() else 'missing','privilege':'usuário',
       'description':'Publicar somente helpers ausentes em ~/.local/bin com SHA-256 do APK; salvar apenas remote/caminho em ~/.config/myct/cloud.json. Não alterar rclone.conf, credenciais, catálogos ou trabalhos. Seleção: '+selection['remote']+' · configuração: '+(selection['config'] or 'caminho padrão descoberto pelo Rclone')},
      {'id':'services','state':'existing' if manager and linger else 'missing','privilege':'sudo se linger ausente',
       'description':'Conferir systemd-run --user. Se ausente, habilitar linger desta conta, mantendo também suas unidades já habilitadas após logout/boot. Não criar daemon, timer, montagem ou iniciar transferência.'},
      {'id':'verify','state':'existing' if compatible else 'missing','privilege':'usuário',
       'description':'Conferir executável >=1.70.0 e integridade dos helpers, somente offline. OAuth pendente não é falha de instalação; acesso online exige aprovação separada.'}]
    facts={'target':'rclone','os':osinfo,'arch':platform.machine(),'user':user,'missing_packages':missing,
           'selection':selection,'config':config,'version':version,'compatible':compatible,'helpers':helpers,'linger':linger,
           'manager':manager,'missing_tools':missing_tools,'selected':selection_ok,'managed':managed,'manifest':digest(MANIFEST),'blockers':blockers}
    complete=not blockers and all(row['state']=='existing' for row in rows)
    journal=[]
    for line in read(state/'events.jsonl').splitlines()[-30:]:
        try:
            event=json.loads(line)
            if event.get('component') in ('packages','rclone','services','verify') and event.get('state') in ('running','installed','existing','failure','complete'):
                journal.append({'component':event['component'],'state':event['state'],'message':'Etapa registrada no Fedora; diagnóstico atual determina a retomada.'})
        except (ValueError,AttributeError): pass
    return {'schema':1,'release':MANIFEST['release'],'facts':facts,'components':rows,'blockers':blockers,
            'actions':[row for row in rows if row['state']=='missing'],'plan_id':digest(facts),
            'can_apply':not blockers and not complete,'complete':complete,'admin':'password_required' if shutil.which('sudo') else 'unavailable',
            'memory_available':int(next((line.split()[1] for line in read('/proc/meminfo').splitlines() if line.startswith('MemAvailable:')),'0'))*1024,
            'disk_available':shutil.disk_usage(Path.home()).free,'optional':['OAuth é configuração humana separada. Diagnóstico não consulta Google Drive nem catálogos.',
            'Disponibilidade online não comprova recuperação do arquivo remoto. Transferências somente sob demanda pelo motor existente.'],
            'journal':journal, 'inventory':{'detected':installed or config['state']!='pending','managed':managed,'installed':installed,'running':False,'enabled':False,'linger':linger,'api_ready':False,'version':version},
            'cloud':{'selection':selection,'config':config,'helpers':helpers,'manager':manager,'online':'not_checked','archive':'not_checked'}}


class CloudInstaller(AddonInstaller):
    def __init__(self,plan,password):
        Installer.__init__(self,plan,password)
        self.state=cloud_paths()[0]

    def apply(self):
        parent=self.state.parent
        if not cloud_guard(parent) or not cloud_guard(parent/'lock'):
            raise SetupError('Caminho do lock divergente; preservar e revisar manualmente.')
        parent.mkdir(parents=True,mode=0o700,exist_ok=True)
        fd=os.open(parent/'lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'a') as lock:
            try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError: raise SetupError('Outra instalação em execução.') from None
            fresh=cloud_diagnose(self.plan['facts']['selection'],ignore_lock=True)
            if fresh['plan_id']!=self.plan['plan_id'] or not fresh['can_apply']:
                raise SetupError('Plano mudou; diagnostique e aprove novamente. Nenhuma instalação iniciada.')
            self.state.mkdir(mode=0o700,exist_ok=True)
            owner=self.state/'owned.json'
            if not owner.exists():
                with owner.open('x') as file:
                    file.write(json.dumps({'schema':1,'component':'rclone','user':fresh['facts']['user']},sort_keys=True))
            try:
                if fresh['facts']['missing_packages']:
                    self.event('packages','running','Instalando Rclone pelo DNF com verificação GPG.')
                    self.sudo(['/usr/bin/dnf','install','-y','--setopt=gpgcheck=1','--setopt=*.gpgcheck=1','--setopt=localpkg_gpgcheck=1','--setopt=install_weak_deps=False','rclone'])
                    self.event('packages','installed','Pacote instalado; configuração OAuth permanece humana.')
                after=cloud_diagnose(fresh['facts']['selection'],ignore_lock=True)
                if after['blockers'] or not after['facts']['version']:
                    raise SetupError('Pré-requisito incompatível/conflitante após DNF; preservar estado e diagnosticar novamente.')
                with zipfile.ZipFile(sys.argv[0]) as bundle:
                    for name in MANIFEST['cloud']['helpers']:
                        source='ops/'+name;data=bundle.read(source)
                        if hashlib.sha256(data).hexdigest()!=MANIFEST['sha256'][source]:
                            raise SetupError('SHA-256 do helper divergente; instalação recusada.')
                        self.publish(cloud_paths()[1]/name,data,0o600 if name.endswith('.py') else 0o700)
                self.publish(cloud_selection_file(),(json.dumps(fresh['facts']['selection'],sort_keys=True)+'\n').encode(),0o600)
                self.event('rclone','installed','Helpers e seleção não secreta disponíveis; configurações anteriores preservadas.')
                if not fresh['facts']['linger']:
                    self.sudo(['/usr/bin/loginctl','enable-linger',fresh['facts']['user']])
                final=cloud_diagnose(fresh['facts']['selection'],ignore_lock=True)
                if not final['complete']:
                    raise SetupError('Instalação parcial; consulte o diagnóstico antes de retomar. OAuth pode continuar pendente.')
                self.event('verify','complete','Instalação offline verificada. OAuth e acesso online são etapas separadas.')
            except (SetupError,OSError,ValueError) as error:
                self.event('rclone','failure',str(error) if isinstance(error,SetupError) else 'Falha local; estado parcial preservado.')
                raise


def cloud_verify(selection, approved):
    plan=cloud_diagnose(selection)
    config=plan['facts']['config']
    if approved!=plan['plan_id'] or not config['configured'] or not plan['facts']['compatible']:
        raise SetupError('Verificação exige diagnóstico atual, remote Drive reconhecido e aprovação explícita.')
    remote=selection['remote']
    flags=['--config',config['path'],'--ask-password=false','--log-level','ERROR','--stats','0','--retries','1','--low-level-retries','1','--contimeout','8s','--timeout','15s']
    reply=cloud_local_command(['about',remote,'--json',*flags],45)
    if reply.returncode:
        # Classify in memory only; never return raw responses, paths or credentials.
        text=reply.stderr.lower()
        code='oauth' if any(word in text for word in (b'401',b'invalid_grant',b'token',b'authenticate')) else 'quota' if any(word in text for word in (b'quota',b'403',b'429')) else 'network'
        return {'schema':1,'state':'failure','code':code,'message':{'oauth':'Autenticação não confirmada; revise OAuth privadamente pelo Terminal.','quota':'Acesso recusado por permissão, limite ou cota; confira a conta.','network':'Rede ou serviço indisponível; nenhum acesso foi presumido.'}[code]}
    try:
        quota=json.loads(reply.stdout)
        if not isinstance(quota,dict) or any(type(v)!=int or v<0 for v in quota.values()): raise ValueError()
    except ValueError:
        return {'schema':1,'state':'failure','code':'response','message':'Resposta de cota inválida; dados brutos omitidos.'}
    folder=cloud_local_command(['lsjson',remote+'My-CT-Archive','--stat',*flags],45)
    accessible=False
    if folder.returncode==0:
        try: accessible=json.loads(folder.stdout).get('IsDir') is True
        except (ValueError,AttributeError): pass
    free=quota.get('free')
    return {'schema':1,'state':'verified','access':True,'archive_accessible':accessible,'archive_recoverable':False,
            'quota_free':free,'message':('Acesso autenticado confirmado. '+('Cota sem espaço livre. ' if free==0 else '')+
            ('Pasta My-CT-Archive acessível. ' if accessible else 'Pasta My-CT-Archive não confirmada; pode ainda não existir. ')+
            'Isso não verifica integridade nem recuperação dos arquivos. Nenhuma transferência iniciada.')}


def cloud_main(args):
    selection=cloud_validate_selection(args.remote,args.config) if args.remote else None
    plan=cloud_diagnose(selection)
    selection=plan['facts']['selection']
    if args.action=='diagnose': emit(plan);return
    if args.action=='verify-cloud': emit(cloud_verify(selection,args.approved));return
    if args.approved!=plan['plan_id'] or (not plan['can_apply'] and not plan['complete']):
        raise SetupError('Plano ausente, bloqueado ou desatualizado; nenhuma alteração.')
    if plan['complete']:
        emit({'schema':1,'state':'complete','message':'Já existente; nenhuma alteração.'});return
    password=bytearray(sys.stdin.buffer.readline(1025).rstrip(b'\n'))
    try:
        if len(password)>1024 or b'\r' in password: raise SetupError('Credencial inválida.')
        CloudInstaller(plan,password).apply()
    finally: password[:]=b'\0'*len(password)
