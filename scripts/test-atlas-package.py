#!/usr/bin/env python3
"""Atlas web packaging regression; optional hosted GET/pull-only old-image audit."""
import argparse
import hashlib
import io
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('atlas_packaging',HERE/'showcase-publish.py')
P=importlib.util.module_from_spec(spec);spec.loader.exec_module(P)


class Packaging(unittest.TestCase):
    def test_stopped_archive_audit_detects_both_missing_vue_and_directory_permission(self):
        for mode,has_index in [(0o444,False),(0o555,True)]:
            jar=io.BytesIO()
            with zipfile.ZipFile(jar,'w') as z:
                if has_index:z.writestr('BOOT-INF/classes/static/index.html','vue')
            raw=io.BytesIO()
            with tarfile.open(fileobj=raw,mode='w') as archive:
                for name in ['backend','backend/target']:
                    m=tarfile.TarInfo(name);m.type=tarfile.DIRTYPE;m.mode=mode;archive.addfile(m)
                m=tarfile.TarInfo('backend/target/knowledge-assistant-0.1.0.jar');m.mode=0o444;m.size=len(jar.getvalue());archive.addfile(m,io.BytesIO(jar.getvalue()))
            value=inspect_backend_archive(raw.getvalue())
            self.assertEqual(value['vue_index'],has_index);self.assertEqual(value['nonroot_path_search'],mode==0o555)
            self.assertEqual(value['jar_sha256'],hashlib.sha256(jar.getvalue()).hexdigest())

    def test_backend_directory_recipe_keeps_traversal_and_nonroot_user(self):
        text=P.recipe('atlas','app',{'python':{'image':'python@sha256:'+'a'*64}})
        self.assertIn('COPY --chmod=0555 backend/',text);self.assertIn('USER 10001:10001',text)

    def test_actual_mode_0444_directory_blocks_ordinary_reader(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)/'backend';folder.mkdir();p=folder/'app.jar';p.write_bytes(b'jar');folder.chmod(0o444)
            try:
                self.assertFalse(os.access(folder,os.X_OK))
                with self.assertRaises(PermissionError):p.read_bytes()
            finally:folder.chmod(0o700)

    def check(self,html=None,assets=None):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'app.jar'
            with zipfile.ZipFile(path,'w') as z:
                if html is not None:z.writestr('BOOT-INF/classes/static/index.html',html)
                for name,raw in (assets or {}).items():z.writestr('BOOT-INF/classes/static/'+name,raw)
            return P.atlas_web_contract(path)

    def test_full_vue_entry_and_assets_are_required(self):
        html='<div id="app"></div><script type="module" src="/assets/app.js"></script><link href="/assets/app.css">'
        self.assertEqual(self.check(html,{'assets/app.js':b'export{}','assets/app.css':b'body{}'})['status'],'PASS')

    def test_default_backend_only_jar_is_rejected(self):
        with self.assertRaises(ValueError):self.check()

    def test_missing_mount_or_module_is_rejected(self):
        for html in ['<h1>Placeholder</h1>','<div id="app"></div>']:
            with self.subTest(html=html),self.assertRaises(ValueError):self.check(html)

    def test_missing_or_empty_asset_is_rejected(self):
        for assets in [{},{'assets/app.js':b''}]:
            with self.subTest(assets=assets),self.assertRaises(ValueError):self.check('<div id="app"></div><script src="/assets/app.js"></script>',assets)

    def test_external_or_traversal_module_is_rejected(self):
        for source in ['https://example.test/app.js','/assets/../app.js','//example.test/app.js']:
            with self.subTest(source=source),self.assertRaises(ValueError):self.check('<div id="app"></div><script src="'+source+'"></script>')

    def test_build_invokes_original_local_web_profile_only_for_atlas(self):
        class Captured(Exception):pass
        for project,repo in [('atlas','ai-knowledge-assistant'),('encore','ticket-platform'),('shop','shop_bak_ap')]:
            with self.subTest(project=project),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);assets=root/'.homelab/release-assets';assets.mkdir(parents=True)
                (assets/'images.lock.json').write_text('{"images":{}}');out=root/'out';out.mkdir();calls=[]
                def run(argv,**kwargs):
                    calls.append(argv)
                    if argv[0]=='mvn':raise Captured()
                    return types.SimpleNamespace(returncode=0)
                with patch.object(P,'BASE',root),patch.object(P.subprocess,'run',run),self.assertRaises(Captured):P.build(project,repo,out,'a'*40)
                maven=calls[-1];self.assertEqual('-Plocal-web' in maven,project=='atlas')


OLD_IMAGE='ghcr.io/elpsy-deng/showcase-atlas-app@sha256:9d3a1a15ec6cddc86630ed3e117a0723727742fa5d4b9c0fe2260646c31e73d9'
OLD_SOURCE='a181be1060205d353d31e009c89c94742a184353'
OLD_JAR='67255571e3b15f7b866a6f609c973a71a40df7859d980b85c99838369b1a1c20'


def audit_existing():
    if not (os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_ENVIRONMENT')=='github-hosted'
      and os.environ.get('GITHUB_REPOSITORY')=='Elpsy-Deng/ai-knowledge-assistant'):
        raise RuntimeError('Only existing repository hosted read-only audit')
    with tempfile.TemporaryDirectory(prefix='atlas-ephemeral-registry-') as tmp:
        env={**os.environ,'DOCKER_CONFIG':tmp}
        def command(args,input=None):
            r=subprocess.run(args,env=env,input=input,capture_output=True,timeout=240)
            if r.returncode:raise RuntimeError('Fixed hosted old-image audit operation failed; private output suppressed')
            return r.stdout
        command(['docker','login','ghcr.io','-u',os.environ['GITHUB_ACTOR'],'--password-stdin'],os.environ['GH_TOKEN'].encode())
        command(['docker','pull',OLD_IMAGE])
        image=json.loads(command(['docker','image','inspect',OLD_IMAGE]))[0]
        labels=image['Config']['Labels']
        if not (OLD_IMAGE in image['RepoDigests'] and labels['org.opencontainers.image.source']=='https://github.com/Elpsy-Deng/ai-knowledge-assistant'
          and labels['org.opencontainers.image.revision']==OLD_SOURCE and image['Config']['User']=='10001:10001'):
            raise RuntimeError('Fixed existing image/source/user verification failed')
        if image['Config'].get('Volumes'):raise RuntimeError('Fixed forensic image must not create volumes')
        cid=command(['docker','create','--network','none','--read-only','--cap-drop=ALL','--entrypoint=python3',OLD_IMAGE,'-c','pass']).decode().strip()
        if not __import__('re').fullmatch('[a-f0-9]{64}',cid):raise RuntimeError('One generated stopped forensic container')
        try:raw=command(['docker','cp',cid+':/app/backend','-'])
        finally:command(['docker','rm',cid])
        value=inspect_backend_archive(raw)
        if value['jar_sha256']!=OLD_JAR:raise RuntimeError('Published artifact JAR identity mismatch')
        value.update(audit_status='PASS',image=OLD_IMAGE,revision=OLD_SOURCE,
          web_contract='PASS' if value['vue_index'] and value['web_assets'] else 'FAIL',
          scope='Existing immutable image content audit only; no model download, registry write or host deployment')
        p=P.BASE/'work/atlas-published-web-audit.json';p.parent.mkdir(exist_ok=True);p.write_text(json.dumps(value,indent=2)+'\n')
        print(json.dumps(value))
        if value['web_contract']!='FAIL' or value['nonroot_path_search']:raise RuntimeError('Previous image defects not reproduced; review evidence')


def inspect_backend_archive(raw):
    if len(raw)>128*1024*1024:raise ValueError('Bounded fixed backend image archive')
    with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        members={m.name.rstrip('/'):m for m in archive.getmembers()}
        modes={}
        for name in ['backend','backend/target']:
            m=members[name]
            if not m.isdir():raise ValueError('Exact backend directory')
            bit=0o100 if m.uid==10001 else 0o010 if m.gid==10001 else 0o001
            modes[name]={'mode':oct(m.mode),'uid':m.uid,'gid':m.gid,'nonroot_search':bool(m.mode&bit)}
        member=members['backend/target/knowledge-assistant-0.1.0.jar']
        if not member.isfile() or member.size>100*1024*1024:raise ValueError('Exact bounded JAR')
        data=archive.extractfile(member).read()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names=z.namelist()
        return {'jar_sha256':hashlib.sha256(data).hexdigest(),'vue_index':'BOOT-INF/classes/static/index.html' in names,
          'web_assets':sum(n.startswith('BOOT-INF/classes/static/assets/') and not n.endswith('/') for n in names),
          'backend_directories':modes,'nonroot_path_search':all(v['nonroot_search'] for v in modes.values())}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--audit-existing-image',action='store_true');args=parser.parse_args()
    if args.audit_existing_image:audit_existing()
    else:unittest.main(argv=[__file__])
