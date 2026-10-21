#!/usr/bin/env python3
"""Real Fedora installation A/B/C in disposable rootless Podman only.
Build image with Containerfile first. No SSH server/ports/privileged container.
"""
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

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_bundle import build

def command(*args, input=None, check=True):
    result = subprocess.run(list(args), input=input, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(f'Fixture command failed ({result.returncode}): {args[:3]}\n' + result.stdout.decode(errors='replace')[-12000:] + result.stderr.decode(errors='replace')[-2000:])
    return result

def validate(bundle, interrupted):
    name = 'myct-setup1-' + uuid.uuid4().hex[:10]
    command('podman', 'run', '-d', '--name', name, '--label', 'io.myct.setup.test=1',
            '--systemd=always', '--memory=1536m', '--cpus=1', '--pids-limit=1024', 'localhost/myct-setup1-test')
    def execute(*args, input=None, check=True, user='tester'):
        return command('podman', 'exec', '-i', '--user', user, '--env', 'HOME=/home/' + user, '--env', 'XDG_RUNTIME_DIR=/run/user/1000', '--env', 'DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus', name, *args, input=input, check=check)
    def cli(action, *args, payload='/tmp/setup.pyz', check=True):
        return execute('python3', payload, action, *args, input=b'\n', check=check)
    def diagnostic():
        return json.loads(cli('diagnose').stdout)
    result = {'scenario': 'C-resume' if interrupted else 'A-clean+B-repeat', 'container': name}
    try:
        for _ in range(20):
            if execute('systemctl', 'show', '--property=Version', check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise AssertionError('Fixture needs systemd + dbus-broker for unprivileged inspection')
        # Emulate the user manager opened by a normal SSH/PAM login, without enabling linger yet.
        execute('systemctl', 'start', 'user@1000.service', user='root')
        command('podman', 'cp', str(bundle / 'payload.pyz'), name + ':/tmp/setup.pyz')
        # Sentinel is an inert test service, created only inside this newly-created fixture.
        sentinel = '[Unit]\nDescription=Unrelated test sentinel\n[Service]\nExecStart=/usr/bin/sleep infinity\n[Install]\nWantedBy=multi-user.target\n'
        execute('python3', '-c', "from pathlib import Path; Path('/etc/systemd/system/prowlarr.service').write_text(" + repr(sentinel) + ')', user='root')
        execute('systemctl', 'daemon-reload', user='root')
        execute('systemctl', 'enable', '--now', 'prowlarr.service', user='root')
        before_pid = execute('systemctl', 'show', 'prowlarr.service', '--property=MainPID', '--value').stdout.strip()
        first = diagnostic()
        assert first['can_apply'], first['blockers']
        assert len(first['actions']) == 5
        denied = cli('apply', '--approved', '0' * 64, check=False)
        assert denied.returncode == 1
        assert execute('test', '!', '-e', '/home/tester/.local/state/myct-setup', check=False).returncode == 0
        if interrupted:
            with tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / 'interrupted.pyz'
                with zipfile.ZipFile(bundle / 'payload.pyz') as source, zipfile.ZipFile(path, 'w') as target:
                    for info in source.infolist():
                        data = source.read(info.filename)
                        if info.filename == '__main__.py':
                            data = data.replace(b"if __name__ == '__main__':", b"Installer.step_postgres = lambda *a: (_ for _ in ()).throw(SetupError('Interrupcao simulada antes do banco'))\n\nif __name__ == '__main__':")
                        target.writestr(info, data)
                command('podman', 'cp', str(path), name + ':/tmp/interrupted.pyz')
            failed = cli('apply', '--approved', first['plan_id'], payload='/tmp/interrupted.pyz', check=False)
            assert failed.returncode == 1
            partial = diagnostic()
            assert partial['can_apply'], partial['blockers']
            assert any(e['component'] == 'core' and e['state'] == 'installed' for e in partial['journal'])
            assert any(e['state'] == 'failure' for e in partial['journal'])
            first = partial
            result['partial_core_preserved'] = True
        applied = cli('apply', '--approved', first['plan_id'])
        events = [json.loads(line) for line in applied.stdout.splitlines()]
        assert events[-1]['state'] == 'complete'
        ready = diagnostic()
        assert ready['complete'] and not ready['actions'], ready['blockers']
        root = '/home/tester/.local/share/myct/postgres'
        saved = execute('env', 'MYCT_PGSOCKET=' + root + '/run', '/usr/local/bin/myct-core', '--record-event', 'setup.validation').stdout.strip()
        event_id = saved.split(b'\t')[0].decode()
        # Second execution must not write receipts, start/restart services or reapply migration.
        journal_before = execute('sha256sum', '/home/tester/.local/state/myct-setup/events.jsonl').stdout
        unit_before = execute('sha256sum', '/etc/systemd/system/myct-core.service', '/home/tester/.config/systemd/user/myct-postgres.service').stdout
        pid_before = execute('systemctl', 'show', 'myct-core.service', '--property=MainPID', '--value').stdout
        repeated = cli('apply', '--approved', ready['plan_id'])
        assert json.loads(repeated.stdout.splitlines()[-1])['state'] == 'complete'
        assert journal_before == execute('sha256sum', '/home/tester/.local/state/myct-setup/events.jsonl').stdout
        assert unit_before == execute('sha256sum', '/etc/systemd/system/myct-core.service', '/home/tester/.config/systemd/user/myct-postgres.service').stdout
        assert pid_before == execute('systemctl', 'show', 'myct-core.service', '--property=MainPID', '--value').stdout
        assert before_pid == execute('systemctl', 'show', 'prowlarr.service', '--property=MainPID', '--value').stdout.strip()
        assert sentinel.encode() == execute('cat', '/etc/systemd/system/prowlarr.service').stdout
        db_setting = execute('psql', '-X', '-w', '-At', '-h', root + '/run', '-d', 'myct', '-c', "SHOW listen_addresses; SHOW unix_socket_permissions; SELECT count(*) FROM public.myct_setup_migrations;").stdout.decode()
        assert db_setting == '\n0700\n1\n', repr(db_setting)
        result.update(complete=True, repeat_no_changes=True, sentinel_preserved=True, migration_count=1,
                      postgres_tcp=False, versions=ready['versions'], payload_sha256=hashlib.sha256((bundle / 'payload.pyz').read_bytes()).hexdigest())
        result['service_memory'] = {
            'core': execute('systemctl', 'show', 'myct-core.service', '--property=MemoryCurrent').stdout.decode().strip(),
            'postgres': execute('systemctl', '--user', 'show', 'myct-postgres.service', '--property=MemoryCurrent').stdout.decode().strip()}
        result['linger'] = execute('loginctl', 'show-user', 'tester', '--property=Linger', '--value').stdout.decode().strip()
        assert result['linger'] == 'yes'
        result['cluster_disk'] = execute('du', '-sh', root).stdout.decode().strip()
        command('podman', 'restart', name)
        # /tmp is intentionally ephemeral across boot, as is the APK's staging area.
        command('podman', 'cp', str(bundle / 'payload.pyz'), name + ':/tmp/setup.pyz')
        for _ in range(30):
            if diagnostic()['complete']:
                break
            time.sleep(1)
        else:
            raise AssertionError('Services did not recover after container restart')
        loaded = execute('env', 'MYCT_PGSOCKET=' + root + '/run', '/usr/local/bin/myct-core', '--get-event', event_id).stdout.strip()
        assert saved == loaded
        result['container_boot_and_event_persistence'] = True
        return result
    finally:
        # This exact fixture was created above; no host services, volumes or databases.
        command('podman', 'rm', '-f', name, check=False)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='myct-setup-validation-') as temporary:
        bundle = Path(temporary); build(bundle)
        results = [validate(bundle, False), validate(bundle, True)]
    Path(args.output).write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))
