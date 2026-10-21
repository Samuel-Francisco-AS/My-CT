#!/usr/bin/env python3
"""Explicit disposable rootless Fedora fixture; never connects to the host DB."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_bundle import build
ROOT=Path(__file__).resolve().parents[3]

def run(*args,input=None):
    result=subprocess.run(args,input=input,capture_output=True)
    if result.returncode:raise RuntimeError('Disposable fixture failed: '+str(args[:4])+'\n'+result.stdout.decode()[-4000:]+result.stderr.decode()[-3000:])
    return result.stdout

def validate(destination):
    name='myct-setup4-'+uuid.uuid4().hex[:10]
    run('podman','run','-d','--name',name,'--label','io.myct.setup.test=4','--systemd=always','--memory=1536m','--cpus=1','--pids-limit=1024','localhost/myct-setup4-test:latest')
    try:
        for _ in range(30):
            ready=subprocess.run(['podman','exec',name,'systemctl','show','--property=Version'],capture_output=True)
            if ready.returncode==0: break
            time.sleep(1)
        run('podman','exec',name,'systemctl','start','user@1000.service')
        with tempfile.TemporaryDirectory(prefix='setup4-bundle-',dir=destination.parent) as temporary:
            bundle=Path(temporary);build(bundle)
            run('podman','cp',str(bundle/'payload.pyz'),name+':/tmp/setup.pyz')
            result=json.loads(run('podman','exec','-i','--user','tester','--env','HOME=/home/tester','--env','XDG_RUNTIME_DIR=/run/user/1000','--env','DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus',name,'python3','-',input=(ROOT/'ops/setup/tests/validate_recovery_inside.py').read_bytes()))
            result['payload_sha256']=hashlib.sha256((bundle/'payload.pyz').read_bytes()).hexdigest()
            result['resource_limit']={'memory_mib':1536,'cpus':1,'pids':1024}
            result['memory_snapshot']=run('podman','stats','--no-stream','--format','{{.MemUsage}}',name).decode().strip()
            destination.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
    finally:subprocess.run(['podman','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

if __name__=='__main__':validate(Path(sys.argv[1]).resolve())
