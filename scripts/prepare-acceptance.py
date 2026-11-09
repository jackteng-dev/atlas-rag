#!/usr/bin/env python3
"""Create an isolated localhost acceptance instance with disposable fictional accounts/data."""
import argparse
import base64
import bcrypt
import hashlib
import json
import os
import pathlib
import secrets
import shutil
import socket
import subprocess
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--instance', required=True)
parser.add_argument('--port', type=int, default=18084)
parser.add_argument('--min-score', type=float, default=-6.0)
parser.add_argument('--jar', default='backend/target/knowledge-assistant-0.1.0.jar')
args = parser.parse_args()
if not args.instance.replace('-', '').isalnum():
    raise SystemExit('Use an alphanumeric instance name')
directory = ROOT / 'work/acceptance' / args.instance
directory.mkdir(parents=True, exist_ok=True)
state = directory / 'instance.json'
if state.exists():
    raise SystemExit('Instance already exists; preserve its evidence and choose a new name')
with socket.socket() as probe:
    probe.bind(('127.0.0.1', args.port))
accounts, profiles = [], {}
for profile, roles, departments in [('reader', ['VIEWER'], ['engineering']), ('hr', ['HR'], ['hr']),
                                     ('outsider', ['HR'], ['engineering']), ('admin', ['ADMIN'], ['hr', 'engineering'])]:
    username, password = 'qa-' + profile, secrets.token_urlsafe(24)
    accounts.append({'username': username, 'passwordHash': bcrypt.hashpw(password.encode(), bcrypt.gensalt(12)).decode(),
                     'roles': roles, 'departments': departments})
    profiles[profile] = {'username': username, 'password': password}
for file, data in [('accounts.json', accounts), ('profiles.json', profiles)]:
    descriptor = os.open(directory / file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(data, output)
artifact = ROOT / args.jar
jar_hash = hashlib.sha256(artifact.read_bytes()).hexdigest()
runtime = directory / f'app-{jar_hash[:16]}.jar'
shutil.copyfile(artifact, runtime)
env = os.environ.copy()
env.update(AKA_PROVIDER='offline', AKA_ALLOW_LOCAL_MODEL='false', AKA_RAG_ENABLED='true',
           AKA_CHUNK_STRATEGY='semantic', AKA_STORE=str(directory / 'store.json'),
           AKA_AUTH_USERS_FILE=str(directory / 'accounts.json'), AKA_RERANK_MIN_SCORE=str(args.min_score),
           AKA_QDRANT_COLLECTION='acceptance_' + args.instance.replace('-', '_'))
java = env.get('AKA_JAVA_BIN') or shutil.which('java')
if not java:
    raise SystemExit('Java required')
with open(directory / 'backend.log', 'ab', buffering=0) as log:
    child = subprocess.Popen([java, '-Xmx512m', '-jar', str(runtime), f'--server.port={args.port}'], cwd=ROOT,
                             env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
base = f'http://127.0.0.1:{args.port}'
info = {'instance': args.instance, 'base': base, 'pid': child.pid, 'runtimeJar': str(runtime),
        'jarSha256': jar_hash, 'profilesFile': str(directory / 'profiles.json'), 'collection': env['AKA_QDRANT_COLLECTION'],
        'minimumRerankerLogit': args.min_score, 'store': str(directory / 'store.json')}
state.write_text(json.dumps(info, indent=2) + '\n')
admin = profiles['admin']
authorization = 'Basic ' + base64.b64encode(f"{admin['username']}:{admin['password']}".encode()).decode()

def call(route, method='GET', body=None):
    request = urllib.request.Request(base + '/api' + route, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': authorization, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=150) as response:
        data = response.read()
        return json.loads(data) if data else None

for attempt in range(90):
    if child.poll() is not None:
        raise SystemExit('Acceptance backend exited; inspect its project-local log')
    try:
        status = call('/status')
        if status['mode'] == 'LOCAL_RAG_GENERATION' and status['actor'] == 'qa-admin':
            break
    except Exception:
        pass
    time.sleep(1)
else:
    child.terminate()
    raise SystemExit('Acceptance backend not ready')
dataset_path = ROOT / 'eval/acceptance/questions.json'
dataset_bytes = dataset_path.read_bytes()
dataset = json.loads(dataset_bytes)
for doc in call('/documents'):
    call(f"/documents/{doc['id']}?expectedVersion={doc['version']}", 'DELETE')
for doc in dataset['documents']:
    text = (ROOT / doc['path']).read_text()
    if hashlib.sha256(text.encode()).hexdigest() != doc['contentHash']:
        raise SystemExit('Frozen fixture changed; do not silently recalibrate against another corpus')
    call('/documents', 'POST', {'id': doc['id'], 'title': doc['title'], 'markdown': text, 'metadata': doc['metadata']})
snapshot = call('/documents')
(directory / 'corpus.json').write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n')
info['datasetSha256'] = hashlib.sha256(dataset_bytes).hexdigest()
info['indexRevision'] = call('/status')['indexRevision']
state.write_text(json.dumps(info, indent=2) + '\n')
print(json.dumps({'result': 'READY', 'instanceFile': str(state), 'base': base, 'pid': child.pid,
                  'documents': len(snapshot), 'jarSha256': jar_hash, 'datasetSha256': info['datasetSha256']}), flush=True)
