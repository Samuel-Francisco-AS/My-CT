# Minimal, legal test fixture: metadata + bytes, not a BitTorrent client/tracker.
import base64
import hashlib
import http.server
import importlib.machinery
import json
from pathlib import Path
import subprocess
import threading
import time
import uuid

import sys
if sys.argv[1:] != ['--run-real-fixture']:
    raise SystemExit('Explicit opt-in required: --run-real-fixture (adds a 64 KiB legal test torrent to the configured real engine)')

m=importlib.machinery.SourceFileLoader('t','ops/myct-torrent').load_module()
rpc=m.Rpc()
content=(b'My-CT directory naming test. CC0 public domain fixture.\n'*2000)[:65536]
name=('MYCT prova ação '+uuid.uuid4().hex[:8]+'.txt').encode()
def b(value):
    if isinstance(value, bytes): return str(len(value)).encode()+b':'+value
    if isinstance(value, int): return b'i'+str(value).encode()+b'e'
    if isinstance(value, dict): return b'd'+b''.join(b(k)+b(v) for k,v in sorted(value.items()))+b'e'
    if isinstance(value, list): return b'l'+b''.join(b(x) for x in value)+b'e'
    raise TypeError()
info={b'length':len(content),b'name':name,b'piece length':16384,
      b'pieces':b''.join(hashlib.sha1(content[i:i+16384]).digest() for i in range(0,len(content),16384))}
key=hashlib.sha1(b(info)).hexdigest()
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        start,end=0,len(content)-1
        if self.headers.get('Range'):
            span=self.headers['Range'].removeprefix('bytes=').split('-')
            start=int(span[0]);end=int(span[1]) if span[1] else end
            self.send_response(206);self.send_header('Content-Range',f'bytes {start}-{end}/{len(content)}')
        else: self.send_response(200)
        data=content[start:end+1]
        self.send_header('Content-Length',str(len(data)));self.end_headers()
        for i in range(0,len(data),4096):
            self.wfile.write(data[i:i+4096]);self.wfile.flush();time.sleep(.1)
    def log_message(self,*args): pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
artifact=Path('artifacts/myct-5c-validation/0.5.4/fixture-real.json')
artifact.parent.mkdir(parents=True, exist_ok=True)
result={'hash':key,'name':name.decode(),'size':len(content),'sha256':hashlib.sha256(content).hexdigest()}
try:
    # The deliberately wrong dn must never become the final directory title.
    magnet=f'magnet:?xt=urn:btih:{key}&dn=ERRADO-SUGESTAO-MAGNET'
    output=subprocess.check_output([str(Path.home()/'.local/bin/myct-torrent'),'add',magnet],text=True)
    result['add']=json.loads(output)
    print('added',output,flush=True)
    # Supply our tiny fixture metadata to the real engine, replacing no files.
    meta=b({b'info':info,b'url-list':f'http://127.0.0.1:{server.server_port}/fixture'.encode()})
    # A duplicate torrent_add does not inject metadata into an existing magnet.
    # Remove/re-add ONLY this synthetic fixture, preserving its pending naming record.
    rpc.call('torrent_remove', {'ids':[key], 'delete_local_data':False})
    result['metadata']=rpc.call('torrent_add',{'metainfo':base64.b64encode(meta).decode(),'download_dir':str(m.destination()/key),'paused':False})
    print('metadata',result['metadata'],flush=True)
    deadline=time.monotonic()+70
    while time.monotonic()<deadline:
        row=rpc.call('torrent_get',{'ids':[key],'fields':m.FIELDS})['torrents'][0]
        result['last']=row
        print(row['metadata_percent_complete'],row['percent_done'],row['status'],row['download_dir'],row['error'],flush=True)
        if row['left_until_done']==0 and row['download_dir']!=str(m.destination()/key): break
        time.sleep(1)
    else: raise RuntimeError('Fixture did not finish/organize in 70 seconds')
    path=Path(row['download_dir'])/name.decode()
    assert hashlib.sha256(path.read_bytes()).hexdigest()==result['sha256']
    assert path.parent.name==m.readable_name(name.decode(),key)
    assert row['status']==6 and row['error']==0
    result['naming']=m.read_naming(key)
    print('PASS',json.dumps(result,ensure_ascii=False),flush=True)
finally:
    artifact.write_text(json.dumps(result,ensure_ascii=False,indent=2))
    server.shutdown();server.server_close();thread.join()
