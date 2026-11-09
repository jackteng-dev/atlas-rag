#!/usr/bin/env python3
"""Exercise real backup/restore and stale-vector cleanup on the disposable lifecycle fixture only."""
import base64
import importlib.util
import json
import pathlib
import subprocess
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONF = 'work/local-lifecycle/config.json'
config = json.loads((ROOT/CONF).read_text())
if config['instance'] != 'lifecycle' or config['store'] != 'work/local-lifecycle/store.json' or not config['models']['reuse']:
    raise ValueError('Only the explicitly disposable lifecycle fixture is authorized')
account = json.loads((ROOT/'work/acceptance/quality-candidate-v4/profiles.json').read_text())['admin']
authorization = 'Basic '+base64.b64encode(f"{account['username']}:{account['password']}".encode()).decode()
def call(route, method='GET', body=None):
    request = urllib.request.Request(f"http://127.0.0.1:{config['port']}/api"+route, method=method,
        data=json.dumps(body).encode() if body else None, headers={'Authorization':authorization,'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=180) as response:
        return json.load(response)
def manager(command):
    result = subprocess.run(['python3','scripts/local-deploy.py',command,'--config',CONF],cwd=ROOT,capture_output=True,text=True,timeout=195)
    if result.returncode: raise RuntimeError(result.stderr)
def query(version):
    answer = call('/questions','POST',{'question':'尚未啟用的課程，要在付款後多久申請退款？'})
    assert answer['status']=='ANSWER_GENERATED'
    assert any('七' in s['text'] or '7' in s['text'] for s in answer['generated'])
    assert answer['sources'] and all(s['version']==version for s in answer['sources'])
    for source in answer['sources']:
        canonical = call(source['href'][4:])
        assert all(canonical[k]==source[k] for k in ('documentId','chunkId','version','excerpt','lineStart','lineEnd'))
    return {'requestId':answer['requestId'],'status':answer['status'],'generated':answer['generated'],
            'sources':answer['sources'],'vectorStage':next(s for s in answer['pipeline'] if s['name']=='vector_index')}
spec=importlib.util.spec_from_file_location('deployment', ROOT/'scripts/local-deploy.py')
deployment=importlib.util.module_from_spec(spec);spec.loader.exec_module(deployment)
manager('start')
try:
    doc=call('/documents/customer-guide')
    archive=ROOT/'work/local-lifecycle/lifecycle-final-backup.zip'
    manifest=deployment.backup(config, archive)
    try:
        deployment.restore(config,archive,True)
        raise AssertionError('Java live-store lock was bypassed')
    except ValueError as error:
        assert 'in use' in str(error)
    updated=call('/documents/customer-guide','PUT',{'title':doc['title'],'markdown':doc['markdown']+'\n\n## 最終還原測試\n本段僅供隔離實例驗證。\n',
        'expectedVersion':doc['version'],'metadata':doc['metadata']})
    before=query(updated['version'])
    (ROOT/'work/local-lifecycle/before-restore-final.json').write_text(json.dumps(before,ensure_ascii=False,indent=2)+'\n')
    print('Updated version and canonical source verified',flush=True)
    manager('stop')
    deployment.restore(config,archive,True)
    manager('start')
    after=query(doc['version'])
    request=urllib.request.Request('http://127.0.0.1:16333/collections/atlas_lifecycle_v4/points/scroll',
        data=json.dumps({'limit':100,'with_payload':True}).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=10) as response:points=json.load(response)['result']['points']
    assert points and all(p['payload']['version']==doc['version'] for p in points)
    models=json.loads((ROOT/'work/model-runtime-status.json').read_text())
    assert all(pathlib.Path(f"/proc/{item['pid']}").exists() for item in models.values())
    report={'javaStoreLockRejectedLiveRestore':True,'activeBackupRevision':manifest['indexRevision'],
            'updatedDocumentVersion':updated['version'],'restoredDocumentVersion':doc['version'],'beforeRestore':before,'afterRestore':after,
            'qdrantRebuiltFromRestoredSource':True,'oldVectorVersionsRemoved':True,'points':len(points),
            'packagedAppRestarted':True,'reusedModelProcessesPreserved':True}
    (ROOT/'work/local-lifecycle/verification-final.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'result':'PASS','output':'work/local-lifecycle/verification-final.json','restoredVersion':doc['version'],'points':len(points)}),flush=True)
finally:
    manager('stop')
