#!/usr/bin/env python3
"""Export a Linux x64 / WSL2 deployment bundle, excluding models, user data and credentials."""
import datetime
import hashlib
import json
import os
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
output = ROOT / f'work/packages/atlas-local-{stamp}.zip'
paths = ['backend/target/knowledge-assistant-0.1.0.jar', 'config/local-deployment.example.json',
         'config/local-rag-artifacts.json', 'scripts/local-deploy.py', 'scripts/local-model-runtime.py',
         'scripts/setup-local-rag.mjs', 'scripts/bootstrap-rag-demo.mjs', 'docs/LOCAL_DEPLOYMENT.md',
         'docs/RAG_STACK.md', 'docs/QUALITY_EVALUATION.md', 'docs/API.md', 'docs/VERIFICATION.md', 'README.md',
         'eval/quality-results.json','eval/quality-holdout.json','eval/quality-calibration.json',
         'eval/quality-human-review.csv','eval/quality-assistant-review.json','eval/ragas-results.json',
         'eval/ragas-controls-calibrated.json','eval/ragas-selection.json',
         'docs/verification/2026-10-05-public-rag-v4-demo.json',
         'docs/verification/milestone4-async-backend-verify.log',
         'docs/verification/milestone4-async-job-auth-verification.json',
         'docs/verification/milestone4-local-lifecycle.json']
paths += [str(path.relative_to(ROOT)) for path in (ROOT/'backend/src/main/resources/rag-corpus-v4').glob('*.md')]
paths += [str(path.relative_to(ROOT)) for path in (ROOT/'backend/src/main/resources/corpus').glob('*.md')]
with zipfile.ZipFile(ROOT / paths[0]) as jar:
    if 'BOOT-INF/classes/static/index.html' not in jar.namelist():
        raise ValueError('Build frontend then Maven local-web profile before packaging')
contents = {path: (ROOT/path).read_bytes() for path in paths}
contents['README.md'] = '''# Atlas 本機部署套件

這是可執行的 Linux x64 / Windows WSL2 套件，並非完整原始碼倉庫。需要 Java 21+、Python 3.10+、Node 24、curl、tar；模型與資料不隨 zip 提供。

從本目錄執行：

```sh
node scripts/setup-local-rag.mjs --download
cp config/local-deployment.example.json config/local-deployment.json
python3 scripts/local-deploy.py start --config config/local-deployment.json
node scripts/bootstrap-rag-demo.mjs http://127.0.0.1:18081
python3 scripts/local-deploy.py status --config config/local-deployment.json
```

瀏覽 http://127.0.0.1:18081 。免費模型下載約 3.82GB；沒有付費模型 API。先核對 package-manifest.json 的檔案 SHA256。
啟停、設定、認證、備份與還原詳見 docs/LOCAL_DEPLOYMENT.md。評分／測試指令需要完整原始碼與其依賴；此套件以網站執行為用途。
'''.encode()
manifest = {'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'platform': 'linux-x64/WSL2',
            'modelsIncluded': False, 'userDataIncluded': False, 'credentialsIncluded': False, 'fullSourceRepositoryIncluded':False,
            'sha256': {name: hashlib.sha256(data).hexdigest() for name, data in contents.items()}}
output.parent.mkdir(parents=True, exist_ok=True)
with os.fdopen(os.open(output, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600), 'wb') as raw, zipfile.ZipFile(raw, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, data in contents.items(): archive.writestr('ai-knowledge-assistant/'+name, data)
    archive.writestr('ai-knowledge-assistant/package-manifest.json', json.dumps(manifest, indent=2)+'\n')
print(json.dumps({'archive': str(output), 'bytes': output.stat().st_size, 'archiveSha256': hashlib.sha256(output.read_bytes()).hexdigest(), **manifest}, indent=2))
