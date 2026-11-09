#!/usr/bin/env python3
"""Keep the optional project's proxy-compliant tunnel alive; app/models are managed separately."""
import json
import os
import pathlib
import signal
import subprocess
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
PORT = int(os.environ.get('AKA_FRONTEND_PORT', '18081'))
if not 1024 <= PORT <= 65535:
    raise SystemExit('Invalid local app port')
if not os.environ.get('HTTPS_PROXY'):
    raise SystemExit('This managed environment tunnel requires the inherited HTTPS_PROXY')
stopping = False
child = None
def stop(_signal, _frame):
    global stopping
    stopping = True
signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
(ROOT/'work/logs').mkdir(parents=True, exist_ok=True)
(ROOT/'work/public-tunnel.pid').write_text(str(os.getpid())+'\n')
environment = os.environ.copy()
environment['AKA_FRONTEND_PORT'] = str(PORT)
try:
    while not stopping:
        if child is None or child.poll() is not None:
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/api/status', timeout=3) as response:
                    if json.load(response)['status'] != 'READY':
                        raise RuntimeError('App not ready')
                with open(ROOT/'work/logs/public-tunnel-v4.log', 'ab', buffering=0) as log:
                    child = subprocess.Popen(['node','scripts/tunnel-proxy.mjs'], cwd=ROOT, env=environment,
                        stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            except Exception:
                time.sleep(2)
                continue
        temporary = ROOT/'work/public-tunnel-status.tmp'
        temporary.write_text(json.dumps({'supervisorPid':os.getpid(),'appPort':PORT,'childPid':child.pid,'exit':child.poll()})+'\n')
        temporary.replace(ROOT/'work/public-tunnel-status.json')
        time.sleep(2)
finally:
    if child and child.poll() is None:
        os.killpg(child.pid,signal.SIGTERM)
        try:child.wait(timeout=10)
        except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL)
