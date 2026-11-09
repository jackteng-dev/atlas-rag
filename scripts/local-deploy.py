#!/usr/bin/env python3
"""Linux/WSL2 local RAG deployment: one web port, owned processes, readiness and versioned backups."""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
import pathlib
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Local health checks must not redirect')

LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def project_path(value):
    path = (ROOT / value).resolve()
    if not path.is_relative_to(ROOT):
        raise ValueError('Deployment paths must stay inside this project; other repositories are not touched')
    return path


def read_config(path):
    config = json.loads(path.read_text())
    if not config['instance'].replace('-', '').isalnum():
        raise ValueError('Invalid instance name')
    ports = [config['port'], *config['models']['ports'].values()]
    if len(ports) != len(set(ports)) or any(type(port) is not int or not 1024 <= port <= 65535 for port in ports):
        raise ValueError('Distinct unprivileged loopback ports required')
    for name in ('jar', 'store'):
        project_path(config[name])
    if config.get('accountsFile'):
        project_path(config['accountsFile'])
    return config


def verify_configured_artifacts(config):
    manifest = json.loads((ROOT/'config/local-rag-artifacts.json').read_text())
    by_name = {item['name']:item['sha256'] for item in manifest['artifacts']}
    expected = {'llmFile':by_name['qwen3-4b-q4.gguf'], 'embeddingFile':by_name['bge-m3-q8.gguf'],
                'rerankerFile':by_name['bge-reranker-v2-m3-q8.gguf'],
                'llamaServer':manifest['executables'][0]['sha256'], 'qdrantBinary':manifest['executables'][1]['sha256']}
    overrides = config['models'].get('artifactSha256', {})
    if set(overrides)-set(expected):
        raise ValueError('Unknown model artifact hash override')
    expected.update(overrides)
    verified = {}
    for name, required in expected.items():
        if not isinstance(required,str) or len(required)!=64 or any(c not in '0123456789abcdef' for c in required):
            raise ValueError('Explicit lowercase SHA256 required for custom artifacts')
        digest = hashlib.sha256()
        with project_path(config['models'][name]).open('rb') as file:
            for block in iter(lambda:file.read(1024*1024), b''):digest.update(block)
        verified[name] = digest.hexdigest()
        if verified[name]!=required:
            raise ValueError('Configured model/runtime checksum mismatch: '+name+'; no component was started')
    return verified


def birth(pid):
    try:
        stat = pathlib.Path(f'/proc/{pid}/stat').read_text()
        return stat.rsplit(')', 1)[1].split()[19]
    except (OSError, IndexError):
        return None


def owned(state):
    try:
        pid = state['supervisorPid']
        args = pathlib.Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
        return (birth(pid) == state['birth'] and state['token'].encode() in args
                and str(ROOT / 'scripts/local-deploy.py').encode() in args
                and pathlib.Path(os.readlink(f'/proc/{pid}/cwd')) == ROOT)
    except (OSError, KeyError):
        return False


def get_json(url):
    with LOCAL_HTTP.open(url, timeout=3) as response:
        return json.load(response)


def health(config):
    ports = config['models']['ports']
    result = {}
    endpoints = {name: f'http://127.0.0.1:{ports[name]}/health' for name in ('llm', 'embedding', 'reranker')}
    endpoints.update(qdrant=f"http://127.0.0.1:{ports['qdrant']}/readyz", app=f"http://127.0.0.1:{config['port']}/api/status", web=f"http://127.0.0.1:{config['port']}/")
    for name, url in endpoints.items():
        try:
            with LOCAL_HTTP.open(url, timeout=3) as response:
                raw = response.read()
                result[name] = response.status == 200
                if name == 'app':
                    body = json.loads(raw)
                    result[name] = body['mode'] == 'LOCAL_RAG_GENERATION' and body['rag']['llm'] == config['models']['llmModel']
                if name == 'web':
                    result[name] = response.status == 200 and b'<div id="app"></div>' in raw
        except Exception:
            result[name] = False
    try:
        model_ids = [item['id'] for item in get_json(f"http://127.0.0.1:{ports['llm']}/v1/models")['data']]
        result['llm'] = result['llm'] and config['models']['llmModel'] in model_ids
    except Exception:
        result['llm'] = False
    result['ready'] = all(result.values())
    return result


def save_state(path, data):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(data, indent=2) + '\n')
    temporary.replace(path)


def supervise(config, config_path, state_path, token):
    stopping = False
    children = {}
    def stop(_signal, _frame):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    directory = state_path.parent
    artifact = project_path(config['jar'])
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    runtime = directory / f'app-{digest[:16]}.jar'
    if not runtime.exists():
        shutil.copyfile(artifact, runtime)
    environment = os.environ.copy()
    ports = config['models']['ports']
    environment.update(AKA_RAG_ENABLED='true', AKA_PROVIDER='offline', AKA_ALLOW_LOCAL_MODEL='false',
        AKA_CHUNK_STRATEGY='semantic', AKA_STORE=str(project_path(config['store'])),
        AKA_AUTH_USERS_FILE=str(project_path(config['accountsFile'])) if config.get('accountsFile') else '',
        AKA_LLM_MODEL=config['models']['llmModel'], AKA_QDRANT_COLLECTION=config['collection'],
        AKA_CANDIDATE_K=str(config['rag']['candidateK']), AKA_RERANK_MIN_SCORE=str(config['rag']['minimumLogit']),
        AKA_RERANK_MAX_GAP=str(config['rag']['maximumLogitGap']))
    for name, variable in [('llm','AKA_LLM_URL'),('embedding','AKA_EMBEDDING_URL'),('reranker','AKA_RERANKER_URL'),('qdrant','AKA_QDRANT_URL')]:
        environment[variable] = f'http://127.0.0.1:{ports[name]}'
    models = config['models']
    environment.update(AKA_LLAMA_SERVER=str(project_path(models['llamaServer'])),
        AKA_QDRANT_BIN=str(project_path(models['qdrantBinary'])), AKA_MODEL_WORKDIR=str(project_path(models['workDirectory'])),
        AKA_LLM_MODEL_PATH=str(project_path(models['llmFile'])), AKA_EMBEDDING_MODEL_PATH=str(project_path(models['embeddingFile'])),
        AKA_RERANKER_MODEL_PATH=str(project_path(models['rerankerFile'])))
    for name, variable in [('llm','AKA_LLM_PORT'),('embedding','AKA_EMBEDDING_PORT'),('reranker','AKA_RERANKER_PORT'),('qdrant','AKA_QDRANT_PORT'),('grpc','AKA_QDRANT_GRPC_PORT')]:
        environment[variable] = str(ports[name])
    if models.get('threads') is not None:
        environment['AKA_MODEL_THREADS'] = str(models['threads'])
    def launch(name, command):
        with open(directory / f'{name}.log', 'ab', buffering=0) as log:
            children[name] = subprocess.Popen(command, cwd=ROOT, env=environment, stdin=subprocess.DEVNULL,
                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    info = {'supervisorPid':os.getpid(), 'birth':birth(os.getpid()), 'token':token, 'configFile':str(config_path),
            'jarSha256':digest, 'state':'STARTING', 'children':{}, 'reusesModels':models['reuse']}
    save_state(state_path, info)
    try:
        if not models['reuse']:
            launch('models', [sys.executable, str(ROOT/'scripts/local-model-runtime.py')])
        for _ in range(120):
            if stopping:
                return
            ready = health(config)
            if all(ready[name] for name in ('llm','embedding','reranker','qdrant')):
                break
            if any(child.poll() is not None for child in children.values()):
                raise RuntimeError('Owned model runtime exited; inspect logs')
            time.sleep(1)
        else:
            raise RuntimeError('Model readiness failed; borrowed models are never started or stopped')
        java = environment.get('AKA_JAVA_BIN') or shutil.which('java')
        if not java:
            raise RuntimeError('Java 21+ required')
        launch('app', [java,'-Xmx512m','-jar',str(runtime),f"--server.port={config['port']}"])
        deadline = time.monotonic() + 180
        while not stopping:
            exited = [name for name,child in children.items() if child.poll() is not None]
            if exited:
                raise RuntimeError('Owned component exited: '+','.join(exited))
            ready = health(config)
            info.update(state='READY' if ready['ready'] else 'STARTING', health=ready,
                        children={name:{'pid':child.pid,'birth':birth(child.pid),'exit':child.poll()} for name,child in children.items()})
            save_state(state_path, info)
            if not ready['ready'] and time.monotonic()>deadline:
                raise RuntimeError('Health check failed; inspect project-local logs')
            time.sleep(2)
    except Exception as error:
        info.update(state='FAILED', error=str(error));save_state(state_path,info)
        raise
    finally:
        for child in children.values():
            if child.poll() is None:
                os.killpg(child.pid,signal.SIGTERM)
        for child in children.values():
            try: child.wait(timeout=15)
            except subprocess.TimeoutExpired: os.killpg(child.pid,signal.SIGKILL)
        if info.get('state')!='FAILED':
            info['state']='STOPPED';save_state(state_path,info)


def backup(config, output):
    store = project_path(config['store'])
    data = store.read_bytes()
    parsed = validate_store(data)
    entries = {'store.json':data,'deployment.json':json.dumps(config,indent=2).encode()}
    if config.get('accountsFile'):
        entries['accounts.json']=project_path(config['accountsFile']).read_bytes()
    manifest = {'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'indexRevision':parsed['revision'],
                'documents':len(parsed['documents']),'sourceIsAuthoritative':True,'vectorsRebuiltOnNextQuery':True,
                'sha256':{name:hashlib.sha256(value).hexdigest() for name,value in entries.items()}}
    output.parent.mkdir(parents=True,exist_ok=True)
    descriptor=os.open(output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,'wb') as raw, zipfile.ZipFile(raw,'w',zipfile.ZIP_DEFLATED) as archive:
        for name,value in entries.items():archive.writestr(name,value)
        archive.writestr('manifest.json',json.dumps(manifest,indent=2))
    return manifest


def validate_store(data):
    parsed = json.loads(data)
    if not isinstance(parsed.get('revision'), int) or not isinstance(parsed.get('documents'), list):
        raise ValueError('Invalid source store')
    for doc in parsed['documents']:
        if hashlib.sha256(doc['markdown'].encode()).hexdigest()!=doc['contentHash']:
            raise ValueError('Corrupt source document hash')
    return parsed


def restore(config, source, replace):
    with zipfile.ZipFile(source) as archive:
        allowed = {'store.json','deployment.json','accounts.json','manifest.json'}
        if len(archive.namelist()) != len(set(archive.namelist())) or not set(archive.namelist()).issubset(allowed):
            raise ValueError('Unexpected backup entries; no extraction was performed')
        manifest=json.loads(archive.read('manifest.json'))
        if not set(manifest['sha256']).issubset(allowed - {'manifest.json'}) or 'store.json' not in manifest['sha256']:
            raise ValueError('Invalid backup manifest')
        entries={name:archive.read(name) for name in manifest['sha256']}
        if any(hashlib.sha256(value).hexdigest()!=manifest['sha256'][name] for name,value in entries.items()):
            raise ValueError('Backup checksum mismatch')
        validate_store(entries['store.json'])
        targets={project_path(config['store']):entries['store.json']}
        if 'accounts.json' in entries:
            if not config.get('accountsFile'):raise ValueError('Restoring protected accounts requires an explicit accountsFile target')
            targets[project_path(config['accountsFile'])]=entries['accounts.json']
        if any(path.exists() for path in targets) and not replace:
            raise ValueError('Target already exists; use --replace only after reviewing backup; previous bytes are retained')
        store = project_path(config['store'])
        store.parent.mkdir(parents=True, exist_ok=True)
        with open(store.with_name(store.name + '.lock'), 'a+b') as guard:
            try:
                fcntl.lockf(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError('Source store is in use by another process; stop its owner before restoring')
            stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
            for target,data in targets.items():
                target.parent.mkdir(parents=True,exist_ok=True)
                if target.exists():
                    previous = target.with_name(target.name+f'.before-restore-{stamp}')
                    with os.fdopen(os.open(previous, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600), 'wb') as output:
                        output.write(target.read_bytes())
                temporary=target.with_name(target.name+'.restore-tmp')
                descriptor=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
                with os.fdopen(descriptor,'wb') as output:output.write(data);output.flush();os.fsync(output.fileno())
                temporary.replace(target)
    return manifest


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('command',choices=['start','stop','status','backup','restore','run'])
    parser.add_argument('--config',default='config/local-deployment.example.json')
    parser.add_argument('--output');parser.add_argument('--archive');parser.add_argument('--replace',action='store_true');parser.add_argument('--token')
    args=parser.parse_args()
    if not pathlib.Path('/proc/self/stat').exists():
        parser.error('This process manager supports Linux/WSL2; native Windows operation is not claimed')
    config_path=project_path(args.config);config=read_config(config_path)
    directory=ROOT/'work/deployment'/config['instance'];directory.mkdir(parents=True,exist_ok=True)
    state_path=directory/'state.json'
    state=json.loads(state_path.read_text()) if state_path.exists() else {}
    alive=owned(state)
    if args.command=='run':
        if not args.token:parser.error('run is an internal supervisor command')
        supervise(config,config_path,state_path,args.token)
    elif args.command=='status':
        result={'ownedSupervisorRunning':alive,'state':state.get('state','NOT_STARTED'),'health':health(config)}
        print(json.dumps(result,indent=2));return 0 if alive and result['health']['ready'] else 1
    elif args.command=='start':
        if alive:raise ValueError('This instance is already running; no process was changed')
        for port in ([config['port']] if config['models']['reuse'] else [config['port'],*config['models']['ports'].values()]):
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(('127.0.0.1',port))
        artifact=project_path(config['jar'])
        with zipfile.ZipFile(artifact) as jar:
            if 'BOOT-INF/classes/static/index.html' not in jar.namelist():
                raise ValueError('Build the local-web Maven profile first; packaged frontend required')
        if not config['models']['reuse']:
            verify_configured_artifacts(config)
        token=secrets.token_hex(16)
        with open(directory/'supervisor.log','ab',buffering=0) as log:
            child=subprocess.Popen([sys.executable,str(ROOT/'scripts/local-deploy.py'),'run','--config',str(config_path),'--token',token],cwd=ROOT,
                                   stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        for _ in range(180):
            if child.poll() is not None:raise RuntimeError('Startup failed; inspect work/deployment instance logs')
            if state_path.exists():
                current=json.loads(state_path.read_text())
                if current.get('token')==token and current.get('state')=='READY':
                    print(json.dumps({'result':'READY','url':f"http://127.0.0.1:{config['port']}",'stateFile':str(state_path)}));return 0
            time.sleep(1)
        if birth(child.pid):os.kill(child.pid,signal.SIGTERM)
        raise RuntimeError('Startup timed out; owned supervisor was stopped')
    elif args.command=='stop':
        if not alive:
            print('No matching owned supervisor; unrelated PIDs are never stopped');return 0
        os.kill(state['supervisorPid'],signal.SIGTERM)
        for _ in range(35):
            if not owned(state):break
            time.sleep(1)
        else:raise RuntimeError('Owned supervisor did not stop; inspect logs')
        print('Stopped only this deployment; reused model services were preserved')
    elif args.command=='backup':
        output=project_path(args.output) if args.output else ROOT/'work/backups'/f"{config['instance']}-{time.time_ns()}.zip"
        print(json.dumps({'archive':str(output),'manifest':backup(config,output)},indent=2))
    elif args.command=='restore':
        if alive:raise ValueError('Stop this deployment before restoring data')
        if not args.archive:parser.error('restore requires --archive')
        print(json.dumps({'restored':restore(config,project_path(args.archive),args.replace)},indent=2))
    return 0

if __name__=='__main__':
    try:sys.exit(main())
    except Exception as error:
        print(f'Deployment failed: {error}',file=sys.stderr);sys.exit(1)
