#!/usr/bin/env python3
"""MYCT-SETUP-1. diagnose is read-only. apply requires an exact, fresh plan hash.
No shell execution, remote downloads, arbitrary command or persistent credential.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import pwd
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

MANIFEST = None  # injected by build_bundle.py

class SetupError(Exception):
    pass

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def read(path):
    try:
        return Path(path).read_text()
    except (OSError, UnicodeError):
        return ''

def run(args, timeout=20, **kwargs):
    try:
        environment = {k: v for k, v in os.environ.items() if k not in
                       ('PGHOSTADDR', 'PGSERVICE', 'PGSERVICEFILE', 'PGPASSWORD', 'PGPASSFILE')}
        environment.update(LC_ALL='C', PGCONNECT_TIMEOUT='3',
                           PGOPTIONS='-c statement_timeout=3000 -c lock_timeout=3000')
        return subprocess.run([str(x) for x in args], capture_output=True, timeout=timeout,
                              env=environment, **kwargs)
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(args, 124, b'', b'')

def ok(args):
    return run(args).returncode == 0

def out(args):
    return run(args).stdout.decode(errors='replace').strip()

def health():
    try:
        # Never honor proxy environment for a loopback probe.
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:8765/v1/health', timeout=3) as response:
            data = json.loads(response.read(16384))
            return data.get('status') == 'ok' and 'ram_total_bytes' in data and 'uptime_seconds' in data
    except (OSError, ValueError):
        return False

def unit_state(name):
    scope = ['--user'] if name == 'myct-postgres.service' else []
    return {k: out(['systemctl', *scope, 'show', name, '--property=' + k, '--value'])
            for k in ('LoadState', 'ActiveState', 'UnitFileState', 'FragmentPath', 'User')}

def psql(root, sql, database='myct'):
    return run(['psql', '-X', '-w', '-A', '-t', '-h', root / 'run', '-p', '5432', '-U', pwd.getpwuid(os.getuid()).pw_name,
                '-d', database, '-v', 'ON_ERROR_STOP=1', '-c', sql])

def db_ready(root, require_receipt=False):
    probe = psql(root, "SELECT id, occurred_at, event_type FROM public.execution_events LIMIT 0")
    conf = psql(root, "SELECT current_setting('listen_addresses') = '' AND current_setting('unix_socket_permissions') = '0700'")
    receipt_ok = True
    if require_receipt:
        receipt = psql(root, 'SELECT sha256 FROM public.myct_setup_migrations WHERE version=1')
        receipt_ok = receipt.returncode == 0 and receipt.stdout.strip().decode() == MANIFEST['sha256']['server/migrations/001_execution_events.sql']
    return probe.returncode == 0 and conf.returncode == 0 and conf.stdout.strip() == b't' and receipt_ok

def paths():
    return Path.home() / '.local/share/myct/postgres', Path.home() / '.local/state/myct-setup'

def unit_text(name, root, user):
    common = ('User=' + user + '\n' if name == 'core' else '') + 'Restart=on-failure\nRestartSec=3s\nNoNewPrivileges=yes\nLimitCORE=0\nUMask=0077\n'
    if name == 'core':
        return ('[Unit]\nDescription=My-CT Core - API local de saude do Fedora\nAfter=local-fs.target\n'
                '[Service]\nType=simple\n' + common + 'ExecStart=/usr/local/bin/myct-core\nRestartPreventExitStatus=203\n'
                f'Environment=MYCT_PGSOCKET={root}/run\n[Install]\nWantedBy=multi-user.target\n')
    return ('[Unit]\nDescription=My-CT PostgreSQL privado\nAfter=local-fs.target\n' + '\n'
            '[Service]\nType=simple\n' + common + f'ExecStart=/usr/bin/postgres -D {root}/data\n'
            'KillSignal=SIGINT\nTimeoutStopSec=120\n[Install]\nWantedBy=default.target\n')

def unit_path(name):
    return (Path.home() / '.config/systemd/user/myct-postgres.service' if name == 'myct-postgres'
            else Path('/etc/systemd/system/myct-core.service'))

def diagnose(ignore_lock=False):
    root, state = paths()
    user = pwd.getpwuid(os.getuid()).pw_name
    osinfo = {}
    for line in read('/etc/os-release').splitlines():
        if '=' in line:
            k, v = line.split('=', 1)
            osinfo[k] = v.strip('"')
    tools = {name: shutil.which(name) for name in ('python3', 'dnf', 'systemctl', 'loginctl', 'rpm', 'sudo', 'make', 'g++', 'pg_config', 'postgres', 'initdb', 'psql', 'restorecon')}
    missing = [p for p in MANIFEST['packages'] if not ok(['rpm', '-q', p])]
    units = {n: unit_state(n + '.service') for n in ('myct-core', 'myct-postgres')}
    linger = out(['loginctl', 'show-user', user, '--property=Linger', '--value']) == 'yes'
    others = {n: {'system': unit_state(n + '.service')['ActiveState'],
                  'user': out(['systemctl', '--user', 'show', n + '.service', '--property=ActiveState', '--value'])}
              for n in ('postgresql', 'prowlarr', 'myct-prowlarr', 'transmission-daemon', 'myct-transmission', 'jellyfin')}
    mem = dict(line.split(':', 1) for line in read('/proc/meminfo').splitlines() if ':' in line)
    available = int(mem.get('MemAvailable', '0 kB').split()[0]) * 1024
    limit, used = read('/sys/fs/cgroup/memory.max').strip(), read('/sys/fs/cgroup/memory.current').strip()
    if limit.isdigit() and used.isdigit():
        available = min(available, max(0, int(limit) - int(used)))
    free_system = shutil.disk_usage('/').free
    free = shutil.disk_usage(Path.home()).free
    blockers = []
    version = int(osinfo.get('VERSION_ID', '0')) if osinfo.get('VERSION_ID', '').isdigit() else 0
    if osinfo.get('ID') != 'fedora' or not MANIFEST['fedora_min'] <= version <= MANIFEST['fedora_max'] or Path('/run/ostree-booted').exists():
        blockers.append('incompatible: Requer Fedora DNF tradicional 43/44; Atomic e outras versões exigem revisão.')
    if platform.machine() not in MANIFEST['architectures']:
        blockers.append('incompatible: Arquitetura sem validação nesta candidata.')
    if os.getuid() == 0 or not re.fullmatch(r'[a-z_][a-z0-9_-]*\$?', user):
        blockers.append('intervention: Conecte como usuário SSH normal com sudo.')
    if not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(Path.home())) or Path.home().resolve() != Path.home():
        blockers.append('intervention: Home precisa de caminho absoluto sem espaços, links ou caracteres especiais nesta candidata.')
    if not tools['dnf'] or not tools['systemctl'] or not tools['rpm'] or not Path('/run/systemd/system').is_dir():
        blockers.append('incompatible: DNF/RPM/systemd de sistema indisponíveis.')
    elif not ok(['systemctl', 'show', '--property=Version']):
        blockers.append('intervention: Conta SSH sem acesso de leitura ao systemd/bus de sistema; revisar a base Fedora.')
    if not ok(['systemctl', '--user', 'show', '--property=Version']):
        blockers.append('intervention: Gerenciador systemd de usuário indisponível; reconectar por SSH/PAM normal antes de instalar.')
    if not missing and any(not tools[name] for name in ('make', 'g++', 'pg_config', 'postgres', 'initdb', 'psql', 'restorecon')):
        blockers.append('intervention: Pacotes presentes mas ferramentas ausentes; revisar instalação RPM antes de continuar.')
    admin = 'password_required' if tools['sudo'] else 'unavailable'
    if tools['sudo'] and ok(['sudo', '-n', '-l']):
        admin = 'available'  # Can list policy; actual authorization still checked per command.
    if not tools['sudo']:
        blockers.append('intervention: sudo ausente; preparar acesso administrativo pessoalmente.')
    versions = {name: out(command).split('\n')[0][:200] for name, command in {
        'gcc': ['g++', '-dumpfullversion'], 'postgres': ['postgres', '--version'],
        'libpq': ['pg_config', '--version'], 'systemd': ['systemctl', '--version'], 'dnf': ['dnf', '--version']}.items()}
    if versions['gcc'] and versions['gcc'].split('.')[0].isdigit() and int(versions['gcc'].split('.')[0]) < 10:
        blockers.append('incompatible: GCC instalado não atende ao C++20; atualização manual requer revisão.')
    if available < 768 * 1024**2 or min(free, free_system) < 2 * 1024**3:
        blockers.append('intervention: Requer ao menos 768 MiB RAM disponíveis e 2 GiB livres para compilar a base.')
    if not ignore_lock and (state / 'lock').is_file():
        try:
            with (state / 'lock').open('r') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            blockers.append('intervention: Instalação ainda em execução; aguarde e consulte novamente.')
    # Refuse ownership/symlink surprises and every foreign installation. Existing healthy legacy is diagnostic only.
    guarded = {root, state, root / 'data', root / 'run', root / 'setup.conf', root / 'data/postgresql.conf',
               state / 'owned.json', state / 'artifacts.json', state / 'events.jsonl', state / 'lock'}
    guarded.update((unit_path('myct-postgres'), unit_path('myct-postgres').parent))
    for target in (root, state, unit_path('myct-postgres').parent):
        guarded.update(p for p in target.parents if p != Path.home() and Path.home() in p.parents)
    for path in sorted(guarded):
        if path.is_symlink() or (path.exists() and path.stat().st_uid != os.getuid()):
            blockers.append('intervention: Diretório privado com dono/link inesperado: ' + str(path))
    core_present = Path('/usr/local/bin/myct-core').exists() or Path('/etc/systemd/system/myct-core.service').exists() or units['myct-core']['LoadState'] == 'loaded'
    core_healthy = health()
    if not core_present:
        try:
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 8765))
        except OSError:
            blockers.append('intervention: Porta 8765 ocupada; não interromper o processo existente.')
    artifacts = {}
    try:
        artifacts = json.loads(read(state / 'artifacts.json') or '{}')
    except ValueError:
        blockers.append('intervention: Recibo de arquivos inválido; revisar manualmente.')
    def recognized(path):
        path = Path(path)
        return path.is_file() and not path.is_symlink() and artifacts.get(str(path)) == hashlib.sha256(path.read_bytes()).hexdigest()
    owned_core = read('/etc/systemd/system/myct-core.service') == unit_text('core', root, user)
    owned_db = read(unit_path('myct-postgres')) == unit_text('postgres', root, user)
    receipt = read(state / 'owned.json')
    managed = receipt == json.dumps({'schema': 1, 'user': user, 'root': str(root)}, sort_keys=True)
    # Ownership receipt permits crash recovery, but never authorizes replacement of differing files.
    if core_present and not (managed and (not Path('/etc/systemd/system/myct-core.service').exists() or owned_core) and recognized('/usr/local/bin/myct-core')):
        blockers.append('intervention: Core pré-existente preservado; esta candidata não assume sua gestão.')
    db_exists = root.exists()
    if db_exists and not managed:
        blockers.append('intervention: Cluster/diretório PostgreSQL pré-existente preservado; sem initdb, migração ou adoção automática.')
    if (units['myct-postgres']['LoadState'] == 'loaded' or unit_path('myct-postgres').exists()) and not (managed and owned_db):
        blockers.append('intervention: Unidade PostgreSQL existente não reconhecida; preservada.')
    if Path('/etc/systemd/system/myct-postgres.service').exists() or out(['systemctl', 'show', 'myct-postgres.service', '--property=LoadState', '--value']) == 'loaded':
        blockers.append('intervention: Unidade de sistema PostgreSQL My-CT preexistente preservada; não duplicar com unidade de usuário.')
    for name, owned in (('myct-core', owned_core), ('myct-postgres', owned_db)):
        location = unit_path(name)
        if location.is_symlink():
            blockers.append('intervention: Unidade em link simbólico preservada: ' + name)
        unit = units[name]
        extra_paths = ([location.with_name(location.name + '.d'), Path('/etc/systemd/user/' + name + '.service.d'),
                        Path('/usr/lib/systemd/user/' + name + '.service.d')] if name == 'myct-postgres' else
                       [Path('/etc/systemd/system/' + name + '.service.d'), Path('/run/systemd/system/' + name + '.service.d'),
                        Path('/usr/lib/systemd/system/' + name + '.service.d')])
        if owned and unit['LoadState'] == 'loaded' and (unit['FragmentPath'] != str(location)
                      or (name == 'myct-core' and unit['User'] != user)
                      or any(path.exists() for path in extra_paths)):
            blockers.append('intervention: Override/unidade com identidade inesperada: ' + name)
    db_ok = db_ready(root, require_receipt=managed) if tools['psql'] and (root / 'data/PG_VERSION').is_file() else False
    if managed and (root / 'data').exists() and not (root / 'data/PG_VERSION').is_file():
        blockers.append('intervention: initdb interrompido: diretório data incompleto preservado; revisar manualmente.')
    if managed and (root / 'data/PG_VERSION').is_file() and tools['postgres']:
        major = out(['postgres', '--version']).split()[-1].split('.')[0]
        if read(root / 'data/PG_VERSION').strip() != major:
            blockers.append('incompatible: Versão major do cluster difere do servidor; não atualizar/reinicializar dados.')
    services_ok = linger and all(u['ActiveState'] == 'active' and u['UnitFileState'] == 'enabled' for u in units.values())
    rows = []
    states = {'packages': not missing, 'core': core_present and core_healthy, 'postgres': db_ok and (not managed or owned_db), 'services': services_ok,
              'verify': core_healthy and db_ok and services_ok and (not managed or owned_db)}
    for entry in MANIFEST['components']:
        rows.append({**entry, 'state': 'existing' if states[entry['id']] else 'missing'})
    actions = [row for row in rows if row['state'] == 'missing']
    facts = {'os': osinfo, 'arch': platform.machine(), 'user': user, 'uid': os.getuid(), 'home': str(Path.home()),
             'missing_packages': missing, 'units': units, 'managed': managed, 'core_present': core_present,
             'db_version': read(root / 'data/PG_VERSION').strip(), 'states': states,
             'unit_hashes': {n: digest(read(unit_path(n))) for n in units}, 'linger': linger,
             'manifest': digest(MANIFEST), 'blockers': blockers, 'versions': versions,
             'artifacts': artifacts, 'config': digest(read(root / 'setup.conf') + read(root / 'data/postgresql.conf'))}
    journal = []
    for line in read(state / 'events.jsonl').splitlines()[-30:]:
        try:
            journal.append(json.loads(line))
        except ValueError:
            pass
    return {'schema': 1, 'release': MANIFEST['release'], 'facts': facts, 'memory_available': available,
            'disk_available': free, 'system_disk_available': free_system, 'tools': tools, 'versions': versions, 'admin': admin, 'other_services': others,
            'components': rows, 'actions': actions, 'blockers': blockers, 'optional': MANIFEST['optional'],
            'plan_id': digest(facts), 'can_apply': not blockers and bool(actions),
            'complete': not blockers and not actions, 'journal': journal}

def emit(value):
    print(json.dumps(value, ensure_ascii=True), flush=True)

def checked(args, timeout=120, input=None):
    result = run(args, timeout=timeout, input=input)
    if result.returncode:
        # Avoid leaking raw program output/config/credentials; retain command identity and exit code only.
        raise SetupError(f'{Path(str(args[0])).name}: falha (código {result.returncode}). Consulte o componente e diagnostique novamente.')
    return result.stdout

class Installer:
    def __init__(self, plan, password):
        self.plan = plan
        self.password = password
        self.root, self.state = paths()

    def event(self, component, state, message):
        event = {'component': component, 'state': state, 'message': message, 'time': int(time.time())}
        with (self.state / 'events.jsonl').open('a') as log:
            log.write(json.dumps(event) + '\n')
            log.flush()
            os.fsync(log.fileno())
        emit(event)

    def sudo(self, args):
        # -k forces this operation's credential, never caches a reusable sudo timestamp.
        return checked(['sudo', '-k', '-S', '-p', '', '--', *map(str, args)], timeout=1800,
                       input=bytes(self.password) + b'\n')

    def install_file(self, source, destination, mode):
        registry = self.state / 'artifacts.json'
        artifacts = json.loads(read(registry) or '{}')
        artifacts[str(destination)] = hashlib.sha256(source.read_bytes()).hexdigest()
        temporary = registry.with_suffix('.tmp')
        temporary.write_text(json.dumps(artifacts, sort_keys=True))
        temporary.replace(registry)
        # -T/no-clobber through hardlink: never overwrite a pre-existing installation.
        if destination.exists() or destination.is_symlink():
            if destination.is_symlink() or source.read_bytes() != destination.read_bytes():
                raise SetupError('Destino existente divergente preservado: ' + str(destination))
            self.sudo(['/usr/sbin/restorecon', destination])
            return
        staged = destination.with_name(destination.name + '.myct-setup-new')
        if staged.is_symlink() or (staged.exists() and staged.read_bytes() != source.read_bytes()):
            raise SetupError('Arquivo de instalação parcial divergente preservado; requer revisão: ' + str(staged))
        if not staged.exists():
            self.sudo(['/usr/bin/install', '-m', mode, source, staged])
        self.sudo(['/usr/bin/ln', '-T', staged, destination])
        self.sudo(['/usr/bin/rm', '--', staged])
        self.sudo(['/usr/sbin/restorecon', destination])

    def apply(self):
        self.state.mkdir(parents=True, mode=0o700, exist_ok=True)
        with (self.state / 'lock').open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise SetupError('Outra instalação está em execução. Consulte novamente; não repetir.')
            fresh = diagnose(ignore_lock=True)
            if fresh['plan_id'] != self.plan['plan_id'] or not fresh['can_apply']:
                raise SetupError('Estado mudou desde a aprovação. Diagnostique e aprove um plano novo.')
            self.state.chmod(0o700)
            owner = self.state / 'owned.json'
            if not owner.exists():
                with owner.open('x') as file:
                    file.write(json.dumps({'schema': 1, 'user': fresh['facts']['user'], 'root': str(self.root)}, sort_keys=True))
            with tempfile.TemporaryDirectory(prefix='myct-setup-') as work:
                work = Path(work)
                with zipfile.ZipFile(sys.argv[0]) as bundle:
                    for name, expected in MANIFEST['sha256'].items():
                        data = bundle.read(name)
                        if hashlib.sha256(data).hexdigest() != expected:
                            raise SetupError('Integridade do pacote inválida.')
                        path = work / name
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(data)
                for row in fresh['components']:
                    component = row['id']
                    if row['state'] == 'existing':
                        self.event(component, 'existing', 'Já existente; preservado.')
                        continue
                    self.event(component, 'running', row['description'])
                    try:
                        getattr(self, 'step_' + component)(work)
                        self.event(component, 'installed', 'Etapa concluída; verificação final confirma disponibilidade.')
                    except (SetupError, OSError) as error:
                        self.event(component, 'failure', str(error) if isinstance(error, SetupError) else 'Falha de arquivo/permissão; estado parcial preservado.')
                        raise SetupError('Instalação interrompida. Componentes concluídos preservados; diagnostique antes de retomar.')

    def step_packages(self, work):
        missing = [p for p in MANIFEST['packages'] if not ok(['rpm', '-q', p])]
        if missing:
            self.sudo(['/usr/bin/dnf', 'install', '-y', '--setopt=install_weak_deps=False', '--setopt=gpgcheck=True', *missing])
        if not all(ok(['rpm', '-q', p]) for p in MANIFEST['packages']):
            raise SetupError('Pacotes ausentes após DNF.')

    def step_core(self, work):
        checked(['make', '-C', work / 'server', '-j1', 'myct-core'], timeout=600)
        checked([work / 'server/myct-core', '--once'])
        self.install_file(work / 'server/myct-core', Path('/usr/local/bin/myct-core'), '0755')
        unit = work / 'core.service'
        unit.write_text(unit_text('core', self.root, self.plan['facts']['user']))
        self.install_file(unit, Path('/etc/systemd/system/myct-core.service'), '0644')

    def step_postgres(self, work):
        self.root.mkdir(parents=True, mode=0o700, exist_ok=True)
        self.root.chmod(0o700)
        data = self.root / 'data'
        (self.root / 'run').mkdir(mode=0o700, exist_ok=True)
        if not data.exists():
            checked(['initdb', '-D', data, '--auth-local=peer', '--auth-host=reject', '--encoding=UTF8', '--no-locale'])
        # Config is written only for our just-created/owned cluster, before first start.
        config = self.root / 'setup.conf'
        expected = ("listen_addresses = ''\nunix_socket_permissions = 0700\nshared_buffers = 16MB\nmax_connections = 10\n"
                    f"unix_socket_directories = '{self.root}/run'\n")
        if config.exists() and config.read_text() != expected:
            raise SetupError('Configuração privada divergente preservada.')
        config.write_text(expected)
        main = data / 'postgresql.conf'
        include = "include = '../setup.conf'\n"
        if include not in main.read_text():
            with main.open('a') as file:
                file.write('\n' + include)
        # Service starts PostgreSQL before database/migration; retries only start an owned unit.
        unit = work / 'postgres.service'
        unit.write_text(unit_text('postgres', self.root, self.plan['facts']['user']))
        destination = unit_path('myct-postgres')
        destination.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        if destination.exists():
            if destination.read_bytes() != unit.read_bytes():
                raise SetupError('Unidade de usuário existente divergente preservada.')
        else:
            with destination.open('xb') as file:
                file.write(unit.read_bytes())
        checked(['systemctl', '--user', 'daemon-reload'])
        checked(['systemctl', '--user', 'enable', '--now', 'myct-postgres.service'])
        for _ in range(30):
            if psql(self.root, 'SELECT 1', 'postgres').returncode == 0:
                break
            time.sleep(1)
        else:
            raise SetupError('PostgreSQL não ficou disponível; revisar journal/SELinux sem alterar política.')
        exists = psql(self.root, "SELECT 1 FROM pg_database WHERE datname='myct'", 'postgres')
        if exists.returncode:
            raise SetupError('Não foi possível consultar o cluster privado.')
        if exists.stdout.strip() != b'1':
            checked(['createdb', '-w', '-h', self.root / 'run', '-p', '5432', '-U', self.plan['facts']['user'], 'myct'])
        # Migration + receipt in one transaction, including crash recovery without replay.
        migration = (work / 'server/migrations/001_execution_events.sql').read_text()
        migration = migration.removeprefix('-- Apply once to the dedicated myct database, as its owner, with ON_ERROR_STOP.\n')
        sha = MANIFEST['sha256']['server/migrations/001_execution_events.sql']
        marker = psql(self.root, "SELECT sha256 FROM public.myct_setup_migrations WHERE version=1")
        if marker.returncode == 0 and marker.stdout.strip().decode() == sha:
            if not db_ready(self.root):
                raise SetupError('Migração registrada mas banco/configuração divergente; revisar sem sobrescrever.')
            return
        if psql(self.root, "SELECT to_regclass('public.execution_events') IS NOT NULL").stdout.strip() == b't':
            raise SetupError('Tabela existente sem recibo compatível; não reaplicar migração.')
        sql = migration.replace('COMMIT;', f"CREATE TABLE public.myct_setup_migrations (version integer PRIMARY KEY, sha256 text NOT NULL);\nINSERT INTO public.myct_setup_migrations VALUES (1, '{sha}');\nCOMMIT;")
        result = psql(self.root, sql)
        if result.returncode or not db_ready(self.root):
            raise SetupError('Falha na migração transacional; dados preservados.')

    def step_services(self, work):
        self.sudo(['/usr/bin/systemctl', 'daemon-reload'])
        self.sudo(['/usr/bin/loginctl', 'enable-linger', self.plan['facts']['user']])
        checked(['systemctl', '--user', 'enable', '--now', 'myct-postgres.service'])
        self.sudo(['/usr/bin/systemctl', 'enable', '--now', 'myct-core.service'])

    def step_verify(self, work):
        for _ in range(15):
            if health() and db_ready(self.root, require_receipt=True):
                break
            time.sleep(1)
        else:
            raise SetupError('Saúde ou banco indisponível; conferir journal das duas unidades My-CT.')
        if out(['loginctl', 'show-user', self.plan['facts']['user'], '--property=Linger', '--value']) != 'yes':
            raise SetupError('Linger não confirmado; banco não tem persistência de boot garantida.')
        for name in ('myct-core.service', 'myct-postgres.service'):
            scope = ['--user'] if name == 'myct-postgres.service' else []
            if not ok(['systemctl', *scope, 'is-active', name]) or not ok(['systemctl', *scope, 'is-enabled', name]):
                raise SetupError('Serviço não ativo/habilitado: ' + name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('diagnose', 'apply', 'verify-cloud', 'backup'))
    parser.add_argument('--approved')
    parser.add_argument('--component', choices=('base', 'transmission', 'prowlarr', 'rclone', 'backup'), default='base')
    parser.add_argument('--remote')
    parser.add_argument('--config', default='')
    args = parser.parse_args()
    os.umask(0o077)
    if MANIFEST is None:
        raise SetupError('Use o pacote gerado por ops/setup/build_bundle.py; não executar o fonte sem manifesto.')
    if args.component == 'backup':
        backup_main(args.action)
        return
    if args.action == 'backup':
        raise SetupError('Operação reservada ao alvo Backup.')
    if args.component == 'rclone':
        cloud_main(args)
        return
    if args.action == 'verify-cloud':
        raise SetupError('Verificação online disponível somente para Rclone.')
    if args.component != 'base':
        addon_main(args)
        return
    plan = diagnose()
    if args.action == 'diagnose':
        emit(plan)
        return
    if args.approved == plan['plan_id'] and plan['complete']:
        for row in plan['components']:
            emit({'component': row['id'], 'state': 'existing', 'message': 'Já existente; sem alterações.'})
        emit({'schema': 1, 'state': 'complete', 'diagnostic': plan})
        return
    if not args.approved or args.approved != plan['plan_id'] or not plan['can_apply']:
        raise SetupError('Plano ausente, bloqueado ou desatualizado. Nenhuma instalação executada.')
    password = bytearray(sys.stdin.buffer.readline(1025).rstrip(b'\n'))
    if len(password) > 1024 or b'\r' in password:
        raise SetupError('Credencial inválida.')
    try:
        Installer(plan, password).apply()
        emit({'schema': 1, 'state': 'complete', 'diagnostic': diagnose()})
    finally:
        password[:] = b'\0' * len(password)

if __name__ == '__main__':
    try:
        main()
    except (SetupError, OSError, ValueError, zipfile.BadZipFile) as error:
        emit({'schema': 1, 'state': 'failure', 'message': str(error) if isinstance(error, SetupError) else 'Falha local; diagnostique novamente. Estado parcial preservado.'})
        sys.exit(1)
