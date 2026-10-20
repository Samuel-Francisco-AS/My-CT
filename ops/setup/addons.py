# SETUP-2 extension, bundled before main by build_bundle.py; uses SETUP-1 transport/guards.
import http.client
import secrets
import stat
import tarfile
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit


def addon_paths(name):
    home = Path.home()
    return {'state': home / '.local/state/myct-setup' / name,
            'config': home / ('.config/myct-transmission' if name == 'transmission' else '.local/share/myct/prowlarr'),
            'unit': home / '.config/systemd/user' / ('myct-' + name + '.service'),
            'program': home / '.local/opt/Prowlarr',
            'helper': home / '.local/bin' / ('myct-torrent' if name == 'transmission' else 'myct-search')}


def addon_unit(name):
    home = Path.home()
    if name == 'transmission':
        return ('[Unit]\nDescription=My-CT Transmission headless (RPC loopback)\n\n[Service]\nType=simple\n'
                'ExecStart=/usr/bin/transmission-daemon --foreground --config-dir %h/.config/myct-transmission\n'
                'UMask=0077\nRestart=on-failure\nRestartSec=5\nNoNewPrivileges=true\n\n[Install]\nWantedBy=default.target\n')
    return ('[Unit]\nDescription=My-CT Prowlarr (HTTP loopback)\n[Service]\nType=simple\n'
            f'ExecStart={home}/.local/opt/Prowlarr/Prowlarr -nobrowser -data={home}/.local/share/myct/prowlarr\n'
            'UMask=0077\nRestart=on-failure\nRestartSec=5\nNoNewPrivileges=true\nTimeoutStopSec=30\n'
            '[Install]\nWantedBy=default.target\n')


def transmission_settings():
    home = Path.home()
    return {'rpc-enabled': True, 'rpc-bind-address': '127.0.0.1', 'rpc-port': 19091,
            'rpc-whitelist-enabled': True, 'rpc-whitelist': '127.0.0.1',
            'rpc-host-whitelist-enabled': True, 'rpc-host-whitelist': 'localhost',
            'rpc-authentication-required': False, 'download-dir': str(home / 'Downloads/My-CT/Torrents'),
            'incomplete-dir-enabled': False, 'port-forwarding-enabled': False,
            'script-torrent-done-enabled': True,
            'script-torrent-done-filename': str(home / '.local/bin/myct-torrent-done')}


def private_xml(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as file:
        meta = os.fstat(file.fileno())
        if not stat.S_ISREG(meta.st_mode) or meta.st_uid != os.getuid() or meta.st_mode & 0o077 or meta.st_size > 65536:
            raise ValueError('private config')
        return ET.fromstring(file.read(65537))


def addon_api(name):
    """Read-only fixed loopback probes; keys/headers/raw responses never returned or logged."""
    conn = None
    try:
        port = MANIFEST['addons'][name]['port']
        conn = http.client.HTTPConnection('127.0.0.1', port, timeout=3)
        if name == 'transmission':
            payload = json.dumps({'jsonrpc':'2.0','id':1,'method':'session_get',
                                  'params':{'fields':['version','config_dir','rpc_version','script_torrent_done_enabled','script_torrent_done_filename']}})
            token = ''
            for _ in range(2):
                conn.request('POST', '/transmission/rpc', payload, {'Content-Type':'application/json','X-Transmission-Session-Id':token})
                response = conn.getresponse()
                if response.status == 409:
                    token = response.getheader('X-Transmission-Session-Id', '')
                    response.read(8192)
                    if not re.fullmatch('[A-Za-z0-9]{1,128}', token):
                        return {'ready': False}
                    continue
                if response.status != 200:
                    return {'ready': False}
                row = json.loads(response.read(65537)).get('result', {})
                version = str(row.get('version', ''))
                compatible = bool(re.match(r'^4\.(?:[1-9]\d*)\b', version))
                return {'ready': compatible, 'version': version[:64],
                        'dedicated': row.get('config_dir') == str(addon_paths(name)['config']),
                        'hook': row.get('script_torrent_done_enabled') is True and
                                row.get('script_torrent_done_filename') == str(Path.home() / '.local/bin/myct-torrent-done')}
        else:
            config = private_xml(addon_paths(name)['config'] / 'config.xml')
            key = config.findtext('ApiKey', '').strip()
            if not re.fullmatch('[a-fA-F0-9]{32}', key):
                return {'ready': False}
            conn.request('GET', '/api/v1/system/status', headers={'X-Api-Key':key})
            response = conn.getresponse()
            if response.status != 200:
                return {'ready': False}
            row = json.loads(response.read(65537))
            version = row.get('version', '')
            return {'ready': row.get('appName') == 'Prowlarr' and isinstance(version, str),
                    'version': version[:64] if isinstance(version, str) and key.lower() not in version.lower() else ''}
    except (OSError, ValueError, ET.ParseError, http.client.HTTPException, AttributeError, TypeError):
        pass
    finally:
        if conn:
            conn.close()
    return {'ready': False}


def addon_service(name, user_scope=True):
    return {key: out(['systemctl', *(['--user'] if user_scope else []), 'show', name + '.service', '--property=' + key, '--value'])
            for key in ('LoadState','ActiveState','UnitFileState','FragmentPath','MainPID','DropInPaths')}


def config_safe(name):
    p = addon_paths(name)
    try:
        if name == 'transmission':
            row = json.loads(read(p['config'] / 'settings.json'))
            # Transmission 4.1 rewrites names to snake_case on shutdown.
            row = {key.replace('_','-'): value for key,value in row.items()}
            return all(row.get(k) == v for k,v in transmission_settings().items())
        row = private_xml(p['config'] / 'config.xml')
        return (row.findtext('BindAddress') == '127.0.0.1' and row.findtext('Port') == '9696'
                and row.findtext('EnableSsl', 'False').lower() == 'false')
    except (OSError, ValueError, ET.ParseError, AttributeError):
        return False


def addon_processes(name):
    result=[]
    for proc in Path('/proc').glob('[0-9]*/comm'):
        command=read(proc).strip().lower()
        if (name=='transmission' and command.startswith('transmission-da')) or (name=='prowlarr' and command=='prowlarr'):
            result.append(proc.parent.name)
    return result


def addon_diagnose(name, ignore_lock=False):
    p = addon_paths(name)
    user = pwd.getpwuid(os.getuid()).pw_name
    manifest = MANIFEST['addons'][name]
    osinfo = dict(line.split('=',1) for line in read('/etc/os-release').splitlines() if '=' in line)
    osinfo = {key:value.strip('"') for key,value in osinfo.items()}
    service = addon_service('myct-' + name)
    other = {f'{scope}:{unit}': addon_service(unit, scope == 'user')
             for scope in ('user','system') for unit in ('myct-' + name, 'transmission-daemon' if name == 'transmission' else 'prowlarr')
             if not (scope == 'user' and unit == 'myct-' + name)}
    missing = [package for package in manifest['packages'] if not ok(['rpm','-q',package])]
    blockers = []
    if osinfo.get('ID') != 'fedora' or osinfo.get('VERSION_ID') not in ('43','44') or Path('/run/ostree-booted').exists():
        blockers.append('incompatible: Requer Fedora DNF tradicional 43/44.')
    if platform.machine() not in MANIFEST['architectures']:
        blockers.append('incompatible: Arquitetura sem artefato validado.')
    if os.getuid() == 0 or not re.fullmatch(r'[a-z_][a-z0-9_-]*\$?', user) or Path.home().resolve() != Path.home() or not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(Path.home())):
        blockers.append('intervention: Requer conta SSH normal e home sem links/espaços/caracteres especiais.')
    if not shutil.which('dnf') or not shutil.which('sudo') or not ok(['systemctl','--user','show','--property=Version']):
        blockers.append('intervention: Requer DNF, sudo e gerenciador de usuário systemd por SSH/PAM.')
    managed = read(p['state'] / 'owned.json') == json.dumps({'schema':1,'component':name,'user':user},sort_keys=True)
    registry = {}
    try:
        registry = json.loads(read(p['state'] / 'files.json') or '{}')
        if not isinstance(registry,dict) or any(not isinstance(k,str) or not isinstance(v,str) for k,v in registry.items()):
            registry={}
            raise ValueError('registry')
    except ValueError:
        blockers.append('intervention: Recibo de arquivos inválido; revisar manualmente.')
    paths = [p['state'],p['unit'],p['config'],p['helper'],p['state']/ 'owned.json',p['state']/ 'files.json',p['state']/ 'events.jsonl']
    if name == 'transmission':
        paths += [p['config']/ 'settings.json',Path.home()/ '.local/bin/myct-torrent-done',Path.home()/ 'Downloads/My-CT/Torrents']
    else:
        paths += [p['program'],p['config']/ 'config.xml',p['program']/ 'Prowlarr',p['program']/ 'Prowlarr.dll']
    guarded = set(paths)
    for path in paths:
        guarded.update(parent for parent in path.parents if Path.home() in parent.parents)
    for path in guarded:
        if path.is_symlink() or (path.exists() and path.stat().st_uid != os.getuid()):
            blockers.append('intervention: Caminho privado com link/dono inesperado: ' + str(path))
    for file, expected in registry.items():
        path = Path(file)
        if path.exists() and (not path.is_file() or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != expected):
            blockers.append('intervention: Artefato existente divergente preservado: ' + str(path))
    detectable = any(path.exists() or path.is_symlink() for path in paths if path != p['state'] and p['state'] not in path.parents)
    detectable |= service['LoadState'] not in ('not-found','')
    alternate = ([Path.home()/'.config/transmission-daemon',Path.home()/'.config/transmission'] if name == 'transmission' else
                 [Path('/opt/Prowlarr'),Path('/usr/lib/prowlarr'),Path.home()/'.config/Prowlarr'])
    detectable |= any(path.exists() for path in alternate)
    foreign_service = any(row['ActiveState'] in ('active','activating') or row['UnitFileState'] in ('enabled','enabled-runtime','masked') for row in other.values())
    foreign_service |= any(row['LoadState'] not in ('not-found','') for key,row in other.items()
                           if key.endswith(':myct-'+name) or name=='prowlarr' or key.startswith('user:'))
    foreign_process = any(pid != service['MainPID'] for pid in addon_processes(name))
    api = addon_api(name)
    detectable |= api['ready'] or foreign_service or foreign_process
    if detectable and not managed:
        blockers.append('intervention: Instalação anterior não gerenciada detectada; somente inspeção, sem adoção, reinstalação ou novo serviço.')
    if managed and (foreign_service or foreign_process):
        blockers.append('intervention: Outra instância detectada; não iniciar uma segunda instância.')
    if managed and p['unit'].exists() and read(p['unit']) != addon_unit(name):
        blockers.append('intervention: Unidade existente divergente preservada.')
    if managed and service['LoadState'] == 'loaded' and service['FragmentPath'] != str(p['unit']):
        blockers.append('intervention: Unidade carregada de caminho diferente; preservada.')
    dropins = service['DropInPaths'].split()
    if managed and any(not value.endswith('/service.d/10-timeout-abort.conf') for value in dropins):
        blockers.append('intervention: Override de unidade existente preservado.')
    config_file = p['config']/('settings.json' if name == 'transmission' else 'config.xml')
    if managed and config_file.exists() and not config_safe(name):
        blockers.append('intervention: Configuração existente incompatível; não reescrever.')
    if not detectable:
        try:
            with socket.socket() as sock:
                sock.bind(('127.0.0.1',manifest['port']))
        except OSError:
            blockers.append('intervention: Porta local ocupada; não interromper processo existente.')
    if name == 'transmission' and shutil.which('transmission-daemon'):
        reply = run(['transmission-daemon','--version'])
        version = (reply.stdout + reply.stderr).decode(errors='replace')
        if not re.search(r'\b4\.[1-9]\d*\b',version):
            blockers.append('incompatible: Helpers exigem Transmission 4.1+; atualização manual de versão antiga requer revisão.')
    linger = out(['loginctl','show-user',user,'--property=Linger','--value']) == 'yes'
    if not ignore_lock and (Path.home()/'.local/state/myct-setup/lock').is_file():
        try:
            with (Path.home()/'.local/state/myct-setup/lock').open('r') as lock:
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            blockers.append('intervention: Outra instalação está em execução; aguarde e consulte novamente.')
    free = shutil.disk_usage(Path.home()).free
    memory = int(next((line.split()[1] for line in read('/proc/meminfo').splitlines() if line.startswith('MemAvailable:')), '0')) * 1024
    if not managed and (free < 1024**3 or memory < 512*1024**2):
        blockers.append('intervention: Requer 1 GiB de disco e 512 MiB RAM disponíveis para instalar o componente.')
    if name == 'prowlarr':
        artifact = manifest['artifacts'].get(platform.machine(),{})
        if not re.fullmatch('[a-f0-9]{64}',artifact.get('sha256','')) or not artifact.get('url','').startswith('https://github.com/Prowlarr/Prowlarr/releases/download/'):
            blockers.append('incompatible: Artefato oficial sem versão/integridade fixadas; instalação bloqueada.')
    files_ok = managed and bool(registry) and all(Path(file).is_file() for file in registry)
    installed = files_ok and p['helper'].is_file() and p['unit'].is_file() and config_safe(name)
    running = service['ActiveState'] == 'active' or (not managed and any(row['ActiveState']=='active' for row in other.values()))
    enabled = service['UnitFileState'] == 'enabled' or (not managed and any(row['UnitFileState']=='enabled' for row in other.values()))
    api_ok = api['ready'] and (name != 'transmission' or (api.get('dedicated') and api.get('hook')))
    complete = bool(installed and running and enabled and linger and api_ok and not missing and not blockers)
    rows = [
        {'id':'packages','state':'existing' if not missing else 'missing','privilege':'sudo','description':'DNF install somente pacotes ausentes: ' + ', '.join(manifest['packages']) + '. Pode resolver/atualizar dependências; GPG obrigatório.'},
        {'id':name,'state':('existing' if installed else 'unmanaged' if detectable and not managed else 'missing'),'privilege':'usuário','description':
         ('Preparar ~/.config/myct-transmission, ~/.local/bin/myct-torrent e hook myct-torrent-done; dados novos em ~/Downloads/My-CT/Torrents. RPC 127.0.0.1:19091; sem alterar limites de upload/peers/seeding.' if name == 'transmission' else
          'Baixar Prowlarr oficial 2.6.5.5623 (~97 MB) com SHA-256 fixado; extrair em ~/.local/opt/Prowlarr. Criar config privada ~/.local/share/myct/prowlarr/config.xml, chave aleatória local, HTTP 127.0.0.1:9696 e helper myct-search. Sem indexadores ou atualização automática.')},
        {'id':'services','state':'existing' if running and enabled and linger else 'unmanaged' if detectable and not managed else 'missing','privilege':'usuário + sudo se linger ausente','description':
         f'Instalar/ativar apenas ~/.config/systemd/user/myct-{name}.service. Se ausente, habilitar linger nesta conta: mantém todas as suas unidades já habilitadas após logout/boot. Sem reiniciar serviços existentes.'},
        {'id':'verify','state':'existing' if complete else 'intervention' if detectable and not managed else 'missing','privilege':'usuário','description':'Conferir configuração loopback, API, unidade active/enabled e linger. Prowlarr precisa de indexadores configurados manualmente depois.'}]
    facts = {'target':name,'os':osinfo,'arch':platform.machine(),'user':user,'missing_packages':missing,'service':service,'other_services':other,
             'managed':managed,'detected':bool(detectable),'api':api,'linger':linger,'files':registry,
             'configuration': digest(read(config_file)), 'manifest':digest(MANIFEST),'blockers':blockers}
    journal=[]
    for line in read(p['state']/ 'events.jsonl').splitlines()[-30:]:
        try:
            journal.append(json.loads(line))
        except ValueError:
            pass
    return {'schema':1,'release':MANIFEST['release'],'facts':facts,'components':rows,
            'actions':[row for row in rows if row['state']=='missing'],'blockers':blockers,
            'plan_id':digest(facts),'can_apply':not blockers and not complete,'complete':complete,
            'memory_available':memory,'disk_available':free,'admin':'password_required' if shutil.which('sudo') else 'unavailable',
            'optional':['Componente opcional: não instala/altera Core ou PostgreSQL.','Indexadores Prowlarr são configurados manualmente por túnel SSH. Rclone fora desta etapa.'],
            'journal':journal,'inventory':{'detected':bool(detectable),'installed':bool(installed),'managed':managed,'running':running,'enabled':enabled,'linger':linger,'api_ready':api_ok,'version':api.get('version','')}}


class OfficialRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        url = urlsplit(newurl)
        if url.scheme != 'https' or url.hostname not in ('github.com','release-assets.githubusercontent.com') or url.username or url.password:
            raise SetupError('Redirect fora do artefato oficial; download bloqueado.')
        return super().redirect_request(request,fp,code,msg,headers,newurl)


def download_official(artifact, destination):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),OfficialRedirect())
    digestor = hashlib.sha256()
    count = 0
    try:
        with opener.open(artifact['url'],timeout=30) as response, destination.open('xb') as file:
            while True:
                data = response.read(65536)
                if not data:
                    break
                count += len(data)
                if count > artifact['size']:
                    raise SetupError('Artefato oficial excedeu tamanho fixado; não executar.')
                digestor.update(data); file.write(data)
    except OSError:
        raise SetupError('Falha ao baixar artefato oficial HTTPS; verificar rede/CA. Nenhum binário não verificado será executado.') from None
    if count != artifact['size'] or digestor.hexdigest() != artifact['sha256']:
        raise SetupError('SHA-256/tamanho do artefato oficial divergente; instalação bloqueada.')


def extract_official(archive, directory):
    with tarfile.open(archive,'r:gz') as tar:
        members = tar.getmembers()
        total = 0
        for entry in members:
            path = Path(entry.name)
            total += entry.size
            if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != 'Prowlarr' or not (entry.isfile() or entry.isdir()) or total > 512*1024**2:
                raise SetupError('Conteúdo do artefato oficial inválido; links/traversal/dispositivos recusados.')
        tar.extractall(directory,members=members,filter='data')
    if not (directory/'Prowlarr/Prowlarr').is_file():
        raise SetupError('Executável ausente no artefato oficial.')


class AddonInstaller(Installer):
    def __init__(self, plan, password):
        super().__init__(plan,password)
        self.name=plan['facts']['target']
        self.p=addon_paths(self.name)
        self.state=self.p['state']

    def publish(self, destination, data, mode=0o700):
        self.state.mkdir(parents=True,mode=0o700,exist_ok=True)
        registry=self.state/'files.json'
        files=json.loads(read(registry) or '{}')
        files[str(destination)]=hashlib.sha256(data).hexdigest()
        temporary=registry.with_suffix('.tmp'); temporary.write_text(json.dumps(files,sort_keys=True)); temporary.replace(registry)
        destination.parent.mkdir(parents=True,mode=0o700,exist_ok=True)
        if destination.exists() or destination.is_symlink():
            if destination.is_symlink() or not destination.is_file() or destination.read_bytes()!=data:
                raise SetupError('Arquivo existente divergente preservado: '+str(destination))
            return
        # Exclusive final name; partial files after interruption remain blocked rather than overwritten.
        with destination.open('xb') as file:
            os.fchmod(file.fileno(),mode); file.write(data); file.flush(); os.fsync(file.fileno())

    def component(self, work):
        if addon_diagnose(self.name,ignore_lock=True)['blockers']:
            raise SetupError('Conflito detectado após pré-requisitos; preservar instalação existente e diagnosticar novamente.')
        p=self.p
        helper='ops/myct-torrent' if self.name=='transmission' else 'ops/myct-search'
        self.publish(p['helper'],(work/helper).read_bytes())
        if self.name=='transmission':
            self.publish(Path.home()/'.local/bin/myct-torrent-done',(work/'ops/torrent/myct-torrent-done').read_bytes())
            config=p['config']/'settings.json'
            if not config.exists():
                config.parent.mkdir(parents=True,mode=0o700,exist_ok=True)
                with config.open('x') as file:
                    json.dump(transmission_settings(),file,indent=2)
            (Path.home()/'Downloads/My-CT/Torrents').mkdir(parents=True,mode=0o700,exist_ok=True)
        else:
            if not p['program'].exists():
                archive=work/'prowlarr.tar.gz'
                artifact=MANIFEST['addons']['prowlarr']['artifacts'][platform.machine()]
                download_official(artifact,archive)
                p['program'].parent.mkdir(parents=True,mode=0o700,exist_ok=True)
                # Stage on destination filesystem: /tmp can be a separate tmpfs.
                with tempfile.TemporaryDirectory(prefix='.myct-prowlarr-',dir=p['program'].parent) as staging:
                    extracted=Path(staging)
                    extract_official(archive,extracted)
                    if p['program'].exists() or p['program'].is_symlink():
                        raise SetupError('Diretório Prowlarr surgiu durante instalação; preservado.')
                    os.rename(extracted/'Prowlarr',p['program'])
            for binary in ('Prowlarr','Prowlarr.dll'):
                path=p['program']/binary
                self.publish(path,path.read_bytes(),0o700 if binary=='Prowlarr' else 0o600)
            config=p['config']/'config.xml'
            if not config.exists():
                p['config'].mkdir(parents=True,mode=0o700,exist_ok=True)
                row=ET.Element('Config')
                for key,value in {'BindAddress':'127.0.0.1','Port':'9696','EnableSsl':'False','LaunchBrowser':'False',
                                  'ApiKey':secrets.token_hex(16),'AuthenticationMethod':'Forms','AuthenticationRequired':'DisabledForLocalAddresses',
                                  'UpdateAutomatically':'False','AnalyticsEnabled':'False','LogLevel':'warn','InstanceName':'My-CT Prowlarr'}.items():
                    ET.SubElement(row,key).text=value
                with config.open('xb') as file:
                    os.fchmod(file.fileno(),0o600);file.write(ET.tostring(row))
        self.publish(p['unit'],addon_unit(self.name).encode(),0o600)

    def services(self):
        if addon_diagnose(self.name,ignore_lock=True)['blockers']:
            raise SetupError('Configuração/instância conflitante; não ativar unidade. Diagnostique novamente.')
        unit='myct-'+self.name+'.service'
        checked(['systemctl','--user','daemon-reload'])
        if out(['loginctl','show-user',self.plan['facts']['user'],'--property=Linger','--value'])!='yes':
            self.sudo(['/usr/bin/loginctl','enable-linger',self.plan['facts']['user']])
        checked(['systemctl','--user','enable',unit])
        if addon_service('myct-'+self.name)['ActiveState']!='active':
            checked(['systemctl','--user','start',unit])

    def apply(self):
        parent=self.state.parent
        parent.mkdir(parents=True,mode=0o700,exist_ok=True)
        with (parent/'lock').open('a') as lock:
            try:
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError:
                raise SetupError('Outra instalação está em execução; aguarde.')
            fresh=addon_diagnose(self.name,ignore_lock=True)
            if fresh['plan_id']!=self.plan['plan_id'] or not fresh['can_apply']:
                raise SetupError('Estado mudou desde a aprovação; diagnostique e aprove novo plano.')
            self.state.mkdir(mode=0o700,exist_ok=True)
            owner=self.state/'owned.json'
            if not owner.exists():
                with owner.open('x') as file:
                    file.write(json.dumps({'schema':1,'component':self.name,'user':fresh['facts']['user']},sort_keys=True))
            with tempfile.TemporaryDirectory(prefix='myct-setup-') as temporary:
                work=Path(temporary)
                with zipfile.ZipFile(sys.argv[0]) as bundle:
                    for name,expected in MANIFEST['sha256'].items():
                        data=bundle.read(name)
                        if hashlib.sha256(data).hexdigest()!=expected:
                            raise SetupError('Integridade do pacote inválida.')
                        target=work/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
                for row in fresh['components']:
                    component=row['id']
                    if row['state']=='existing':
                        self.event(component,'existing','Já existente; preservado.');continue
                    self.event(component,'running',row['description'])
                    try:
                        if component=='packages':
                            if fresh['facts']['missing_packages']:
                                self.sudo(['/usr/bin/dnf','install','-y','--setopt=install_weak_deps=False','--setopt=gpgcheck=True',*fresh['facts']['missing_packages']])
                            if not all(ok(['rpm','-q',package]) for package in MANIFEST['addons'][self.name]['packages']):
                                raise SetupError('Pacotes ausentes após DNF.')
                        elif component==self.name:
                            self.component(work)
                        elif component=='services':
                            self.services()
                        else:
                            for _ in range(45):
                                if addon_diagnose(self.name,ignore_lock=True)['complete']:
                                    break
                                time.sleep(1)
                            else:
                                raise SetupError('Verificação falhou. Consultar journalctl --user -u myct-'+self.name+'.service; preservar SELinux/configurações.')
                        self.event(component,'installed','Etapa concluída; estado real consultado na verificação final.')
                    except (SetupError,OSError,tarfile.TarError) as error:
                        reason=(str(error) if isinstance(error,SetupError) else
                                'Falha de arquivo/permissão (errno '+str(error.errno)+')' if isinstance(error,OSError) else
                                'Arquivo oficial inválido na extração')
                        self.event(component,'failure',reason+'; dados preservados. Diagnostique antes de retomar.')
                        raise SetupError(reason) from None


def addon_main(args):
    plan=addon_diagnose(args.component)
    if args.action=='diagnose':
        emit(plan);return
    if not args.approved or args.approved!=plan['plan_id'] or (not plan['can_apply'] and not plan['complete']):
        raise SetupError('Plano ausente, bloqueado ou desatualizado. Nenhuma instalação executada.')
    if plan['complete']:
        emit({'schema':1,'state':'complete','diagnostic':plan});return
    password=bytearray(sys.stdin.buffer.readline(1025).rstrip(b'\n'))
    if len(password)>1024 or b'\r' in password:
        raise SetupError('Credencial inválida.')
    try:
        AddonInstaller(plan,password).apply()
        final=addon_diagnose(args.component)
        if not final['complete']:
            raise SetupError('Conclusão não confirmada; consulte novamente.')
        emit({'schema':1,'state':'complete','diagnostic':final})
    finally:
        password[:]=b'\0'*len(password)
