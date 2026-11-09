#!/usr/bin/env python3
"""Keep only this project's demo children alive in independent sessions; optional free WSS tunnel."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import signal
import socket
import subprocess
import time
import urllib.request

root = pathlib.Path(__file__).resolve().parent.parent
os.chdir(root)
parser = argparse.ArgumentParser()
parser.add_argument('--rag', action='store_true')
parser.add_argument('--tunnel', action='store_true')
parser.add_argument('--backend-port', type=int, default=18081)
parser.add_argument('--frontend-port', type=int, default=4175)
args = parser.parse_args()
for port in [args.backend_port, args.frontend_port]:
    with socket.socket() as probe:
        try:
            probe.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'Loopback port {port} is occupied; stop only a verified own demo or choose another port')
work = root / 'work'
(work / 'logs').mkdir(parents=True, exist_ok=True)
(work / 'runtime').mkdir(exist_ok=True)
artifact = root / 'backend/target/knowledge-assistant-0.1.0.jar'
identity = hashlib.sha256(artifact.read_bytes()).hexdigest()[:16]
runtime_jar = work / f'runtime/demo-{identity}.jar'
if not runtime_jar.exists():
    shutil.copyfile(artifact, runtime_jar)
environment = os.environ.copy()
environment.update(AKA_PROVIDER='offline', AKA_ALLOW_LOCAL_MODEL='false',
    AKA_RAG_ENABLED='true' if args.rag else 'false', AKA_CHUNK_STRATEGY='semantic' if args.rag else 'structural',
    AKA_STORE=str(work / ('rag-demo-store.json' if args.rag else 'demo-store.json')),
    AKA_API_ORIGIN=f'http://127.0.0.1:{args.backend_port}',
    AKA_FRONTEND_PORT=str(args.frontend_port), __VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS='.tunnelmole.net')
java = environment.get('AKA_JAVA_BIN') or shutil.which('java')
if not java:
    raise SystemExit('Java runtime required')
commands = {
    'backend': [java, '-Xmx512m', '-jar', str(runtime_jar), f'--server.port={args.backend_port}'],
    'frontend': ['node', 'node_modules/vite/bin/vite.js', 'preview', '--host', '127.0.0.1', '--port', str(args.frontend_port), '--strictPort'],
}
if args.tunnel:
    commands['tunnel'] = ['node', 'scripts/tunnel-proxy.mjs']
children = {}
stopping = False

def stop(_signal, _frame):
    global stopping
    stopping = True

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
(work / 'demo-supervisor.pid').write_text(str(os.getpid()) + '\n')
try:
    while not stopping:
        for name, command in commands.items():
            if name in children and children[name].poll() is None:
                continue
            if name == 'tunnel':
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{args.frontend_port}/api/status', timeout=3) as response:
                        json.load(response)
                except Exception:
                    continue
            with open(work / f'logs/{name}-supervised.log', 'ab', buffering=0) as log:
                children[name] = subprocess.Popen(command, cwd=root / 'frontend' if name == 'frontend' else root,
                    env=environment, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            print(f'Started {name} pid={children[name].pid}', flush=True)
        status = {'supervisorPid': os.getpid(), 'rag': args.rag, 'runtimeJar': str(runtime_jar),
            'children': {name: {'pid': child.pid, 'exit': child.poll()} for name, child in children.items()}}
        temporary = work / 'demo-supervisor-status.tmp'
        temporary.write_text(json.dumps(status) + '\n')
        temporary.replace(work / 'demo-supervisor-status.json')
        time.sleep(2)
finally:
    for child in children.values():
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
    for child in children.values():
        try:
            child.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
