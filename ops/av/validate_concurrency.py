#!/usr/bin/python3
"""Opt-in real update concurrency test: network request via existing user service."""
import json
import os
from pathlib import Path
import pwd
import subprocess
import tempfile
import time

home = Path(pwd.getpwuid(os.getuid()).pw_dir)
cli = home / '.local/bin/myct-av'
unit = 'myct-av-update.service'
with tempfile.TemporaryDirectory(prefix='myct-av-concurrent-', dir=home) as tmp:
    f = Path(tmp) / 'benign'; f.write_text('My-CT concurrency proof')
    scan = subprocess.Popen([str(cli), 'scan', str(f).encode().hex()], stdout=subprocess.PIPE)
    deadline = time.monotonic() + 5
    while not subprocess.run(['pgrep', '-x', 'clamscan'], capture_output=True).stdout:
        assert time.monotonic() < deadline
        time.sleep(.05)
    requests = [subprocess.Popen([str(cli), 'update'], stdout=subprocess.PIPE) for _ in range(8)]
    for p in requests:
        assert json.loads(p.communicate(timeout=5)[0])['requested']
    status = json.loads(subprocess.check_output([str(cli), 'update-status']))
    assert status['running'] and status['result'] == 'RUNNING', status
    assert status['phase'] == 'WAITING', status
    assert not subprocess.run(['pgrep', '-x', 'freshclam'], capture_output=True).stdout
    invocation = subprocess.check_output(['systemctl', '--user', 'show', unit, '-p', 'InvocationID', '--value']).decode().strip()
    assert json.loads(scan.communicate(timeout=190)[0])['state'] == 'CLEAN'
    max_processes = 0
    deadline = time.monotonic() + 60
    while True:
        processes = subprocess.run(['pgrep', '-x', 'freshclam'], capture_output=True).stdout.splitlines()
        max_processes = max(max_processes, len(processes))
        status = json.loads(subprocess.check_output([str(cli), 'update-status']))
        if not status['running']:
            break
        assert time.monotonic() < deadline
        time.sleep(.05)
    assert max_processes <= 1
    assert status['result'] == 'SUCCESS', status
    # FreshClam stdout log has one start; all eight starts coalesced into one invocation.
    assert status['progress'].count('ClamAV update process started') == 1, status
    print(json.dumps({'requests': 8, 'waiting_during_scan': True, 'invocation': invocation,
                      'sampled_max_freshclam': max_processes, 'update': status}, indent=2))
