"""Network containment and malformed-judge guards; deterministic stubs, not quality measurements."""
import json
import pathlib
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from ragas_local import LoopbackJson, LocalJudge
from ragas.metrics.collections.faithfulness.util import NLIStatementOutput


class EvaluationGuards(unittest.TestCase):
    def test_remote_credentials_and_paths_are_rejected_before_requests(self):
        for origin in ['https://api.openai.com','http://user:password@127.0.0.1:18111','http://localhost:18111/path','http://localhost:18111?key=x']:
            with self.assertRaises(ValueError): LoopbackJson(origin)

    def test_redirect_is_not_followed_even_to_another_loopback_service(self):
        counts={'first':0,'second':0}
        class Second(BaseHTTPRequestHandler):
            def do_GET(self): counts['second']+=1; self.send_response(200); self.end_headers()
            def log_message(self,*args): pass
        target=ThreadingHTTPServer(('127.0.0.1',0),Second)
        class First(BaseHTTPRequestHandler):
            def do_POST(self):
                counts['first']+=1;self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(302);self.send_header('Location',f'http://127.0.0.1:{target.server_port}/blocked');self.end_headers()
            def log_message(self,*args): pass
        source=ThreadingHTTPServer(('127.0.0.1',0),First)
        workers=[threading.Thread(target=server.serve_forever,daemon=True) for server in [target,source]]
        for worker in workers:worker.start()
        try:
            with self.assertRaisesRegex(ValueError,'redirect'):LoopbackJson(f'http://127.0.0.1:{source.server_port}').post('/v1',{})
            self.assertEqual(counts,{'first':1,'second':0})
        finally:
            for server in [source,target]:server.shutdown();server.server_close()

    def test_rewritten_statements_never_receive_a_faithfulness_score_or_cache(self):
        root=pathlib.Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory(dir=root/'work') as directory:
            judge=LocalJudge('http://127.0.0.1:18111','fixture',directory,'fixture-only')
            judge.http.post=lambda *_args:{'choices':[{'finish_reason':'stop','message':{'content':json.dumps({'statements':[{'statement':'錯誤改寫','reason':'','verdict':1}]})}}],
                'usage':{'prompt_tokens':10,'completion_tokens':5}}
            prompt='Now perform the same with the following input\ninput: '+json.dumps({'context':'來源','statements':['原始事實']})
            with self.assertRaisesRegex(ValueError,'rewrote'):judge.generate(prompt,NLIStatementOutput)
            self.assertEqual(list(pathlib.Path(directory).iterdir()),[])
            self.assertEqual(judge.traces[0]['usage']['completion_tokens'],5)
            self.assertIsNone(judge.traces[0]['judgment'])


if __name__=='__main__':unittest.main()
