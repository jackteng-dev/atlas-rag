#!/usr/bin/env python3
"""Project-local free model/Qdrant processes. No download, Docker, global services or remote inference."""
import json
import os
import pathlib
import signal
import socket
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
os.chdir(ROOT)
WORK = ROOT / os.environ.get('AKA_MODEL_WORKDIR', 'work')
ports = {name: int(os.environ.get(variable, default)) for name,variable,default in [
    ('llm','AKA_LLM_PORT','18111'),('embedding','AKA_EMBEDDING_PORT','18112'),
    ('reranker','AKA_RERANKER_PORT','18113'),('qdrant','AKA_QDRANT_PORT','16333'),('grpc','AKA_QDRANT_GRPC_PORT','16334')]}
if len(set(ports.values())) != 5 or any(port < 1024 or port > 65535 for port in ports.values()):
    raise SystemExit('Model ports must be distinct unprivileged ports')
for port in ports.values():
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'Local RAG port {port} is occupied; stop only a verified own runtime first')
(WORK / 'logs').mkdir(parents=True, exist_ok=True)
(WORK / 'tmp').mkdir(exist_ok=True)
(WORK / 'qdrant').mkdir(exist_ok=True)
config = WORK / 'qdrant/config.yaml'
config.write_text(f'''telemetry_disabled: true
storage:
  storage_path: {WORK / 'qdrant/storage'}
  snapshots_path: {WORK / 'qdrant/snapshots'}
service:
  host: 127.0.0.1
  http_port: {ports['qdrant']}
  grpc_port: {ports['grpc']}
''')
llama = ROOT / os.environ.get('AKA_LLAMA_SERVER', 'work/tools/llama/llama-b11401/llama-server')
available_cpu = os.cpu_count() or 1
try:
    quota, period = pathlib.Path('/sys/fs/cgroup/cpu.max').read_text().split()
    if quota != 'max': available_cpu = min(available_cpu, max(1, int(quota) // int(period)))
except (OSError, ValueError):
    pass
threads = str(int(os.environ.get('AKA_MODEL_THREADS', min(4, available_cpu))))
if not 1 <= int(threads) <= 64: raise SystemExit('Model threads must be 1–64')
common = [str(llama), '--host', '127.0.0.1', '--threads', threads, '--threads-batch', threads,
          '--parallel', '1', '--ctx-size', '4096', '--no-webui']
commands = {
    'embedding': common + ['--port', str(ports['embedding']), '--model', str(ROOT / os.environ.get('AKA_EMBEDDING_MODEL_PATH', 'work/models/bge-m3-q8.gguf')),
                           '--alias', 'bge-m3', '--embedding', '--pooling', 'cls'],
    'reranker': common + ['--port', str(ports['reranker']), '--model', str(ROOT / os.environ.get('AKA_RERANKER_MODEL_PATH', 'work/models/bge-reranker-v2-m3-q8.gguf')),
                          '--alias', 'bge-reranker-v2-m3', '--embedding', '--pooling', 'rank', '--rerank'],
    'llm': common + ['--port', str(ports['llm']), '--model', str(ROOT / os.environ.get('AKA_LLM_MODEL_PATH', 'work/models/qwen3-4b-q4.gguf')),
                     '--alias', os.environ.get('AKA_LLM_MODEL', 'qwen3-4b-instruct-2507'), '--batch-size', '128', '--ubatch-size', '128', '--n-predict', '768'],
    'qdrant': [str(ROOT / os.environ.get('AKA_QDRANT_BIN', 'work/tools/qdrant/qdrant')), '--config-path', str(config)],
}
environment = os.environ.copy()
environment['TMPDIR'] = str(WORK / 'tmp')
children = {}
stopping = False

def stop(_signal, _frame):
    global stopping
    stopping = True

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
(WORK / 'model-runtime.pid').write_text(str(os.getpid()) + '\n')
try:
    for name, command in commands.items():
        with open(WORK / f'logs/{name}.log', 'ab', buffering=0) as log:
            children[name] = subprocess.Popen(command, cwd=WORK / 'qdrant' if name == 'qdrant' else ROOT, env=environment,
                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        print(f'Started {name} PID {children[name].pid}', flush=True)
    while not stopping:
        snapshot = {name: {'pid': child.pid, 'exit': child.poll()} for name, child in children.items()}
        (WORK / 'model-runtime-status.json').write_text(json.dumps(snapshot) + '\n')
        failed = [name for name, value in snapshot.items() if value['exit'] is not None]
        if failed:
            raise SystemExit('Runtime component exited: ' + ', '.join(failed) + '; inspect project work/logs')
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
