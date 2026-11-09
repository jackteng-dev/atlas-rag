"""Destructive restore guards and process ownership; disposable project-local fixtures only."""
import hashlib
import importlib.util
import json
import os
import pathlib
import subprocess
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('deploy', pathlib.Path(__file__).with_name('local-deploy.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentSafety(unittest.TestCase):
    def setUp(self):
        (deploy.ROOT / 'work').mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=deploy.ROOT / 'work')
        self.root = pathlib.Path(self.directory.name)
        self.store = self.root / 'store.json'
        self.config = {'store': str(self.store), 'accountsFile': None}
        self.original = json.dumps({'revision': 4, 'documents': [{'markdown': '來源一', 'contentHash': hashlib.sha256('來源一'.encode()).hexdigest()}]}).encode()
        self.store.write_bytes(self.original)
        self.archive = self.root / 'snapshot.zip'
        deploy.backup(self.config, self.archive)

    def tearDown(self):
        self.directory.cleanup()

    def test_restore_retains_previous_bytes_and_private_permissions(self):
        altered = self.original.replace(b'4', b'5', 1)
        self.store.write_bytes(altered)
        with self.assertRaises(ValueError): deploy.restore(self.config, self.archive, False)
        self.assertEqual(self.store.read_bytes(), altered)
        deploy.restore(self.config, self.archive, True)
        self.assertEqual(self.store.read_bytes(), self.original)
        retained = list(self.root.glob('store.json.before-restore-*'))
        self.assertEqual(len(retained), 1)
        self.assertEqual(retained[0].read_bytes(), altered)
        for file in (self.store, retained[0], self.archive): self.assertEqual(file.stat().st_mode & 0o777, 0o600)

    def test_checksums_and_source_hashes_precede_mutation(self):
        with zipfile.ZipFile(self.archive) as archive: entries = {n: archive.read(n) for n in archive.namelist()}
        for update_manifest in (False, True):
            entries['store.json'] = self.original.replace('來源一'.encode(), '來源二'.encode()) if '來源一'.encode() in self.original else json.dumps({'revision': 4, 'documents': [{'markdown': '錯誤', 'contentHash': '0'*64}]}).encode()
            if update_manifest:
                manifest = json.loads(entries['manifest.json'])
                manifest['sha256']['store.json'] = hashlib.sha256(entries['store.json']).hexdigest()
                entries['manifest.json'] = json.dumps(manifest).encode()
            corrupt = self.root / f'corrupt-{update_manifest}.zip'
            with zipfile.ZipFile(corrupt, 'w') as archive:
                for name, data in entries.items(): archive.writestr(name, data)
            with self.assertRaises(ValueError): deploy.restore(self.config, corrupt, True)
            self.assertEqual(self.store.read_bytes(), self.original)

    def test_java_compatible_lock_blocks_restore_from_another_process(self):
        command = ['python3', '-c', 'import fcntl,sys; f=open(sys.argv[1],"a+b"); fcntl.lockf(f,fcntl.LOCK_EX); print("locked",flush=True); sys.stdin.read()', str(self.store)+'.lock']
        child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(child.stdout.readline().strip(), 'locked')
            with self.assertRaisesRegex(ValueError, 'in use'): deploy.restore(self.config, self.archive, True)
            self.assertEqual(self.store.read_bytes(), self.original)
        finally:
            child.communicate(input='', timeout=3)
        deploy.restore(self.config, self.archive, True)

    def test_foreign_pid_cannot_be_stopped_or_path_escape(self):
        self.assertFalse(deploy.owned({'supervisorPid': os.getpid(), 'birth': deploy.birth(os.getpid()), 'token': 'fictional-token'}))
        with self.assertRaises(ValueError): deploy.project_path('../e-commerce-platform/store.json')
        with self.assertRaises(FileExistsError): deploy.backup(self.config, self.archive)

    def test_changed_model_bytes_block_startup_verification(self):
        models = {'artifactSha256':{}}
        for name in ('llmFile','embeddingFile','rerankerFile','llamaServer','qdrantBinary'):
            path = self.root / name
            path.write_bytes(('synthetic-'+name).encode())
            models[name]=str(path)
            models['artifactSha256'][name]=hashlib.sha256(path.read_bytes()).hexdigest()
        self.assertEqual(len(deploy.verify_configured_artifacts({'models':models})),5)
        pathlib.Path(models['llmFile']).write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError,'no component was started'):
            deploy.verify_configured_artifacts({'models':models})


if __name__ == '__main__': unittest.main()
