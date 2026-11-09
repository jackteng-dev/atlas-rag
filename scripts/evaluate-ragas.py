#!/usr/bin/env python3
"""Offline Ragas batch judgments; no question-path latency or paid provider calls."""
import argparse
import asyncio
import datetime
import hashlib
from importlib.metadata import version
import json
import math
import pathlib
import statistics
from ragas_local import LocalJudge, LocalEmbeddings, metrics

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER_SHA256 = hashlib.sha256((ROOT / 'scripts/ragas_local.py').read_bytes()).hexdigest()
parser = argparse.ArgumentParser()
parser.add_argument('artifact', nargs='?')
parser.add_argument('--controls', action='store_true')
parser.add_argument('--output')
parser.add_argument('--ids', help='Explicit comma-separated sample IDs; default is all generated answers')
parser.add_argument('--llm-origin', default='http://127.0.0.1:18111')
parser.add_argument('--embedding-origin', default='http://127.0.0.1:18112')
parser.add_argument('--model', default='qwen3-4b-instruct-2507')
parser.add_argument('--strictness', type=int, choices=[1, 3], default=1)
args = parser.parse_args()
if not args.controls and not args.artifact:
    parser.error('Provide an acceptance artifact or --controls')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
output = pathlib.Path(args.output) if args.output else ROOT / f'work/evaluation/ragas-{stamp}.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.open('x').close()
journal = output.with_suffix('.jsonl').open('x')
identity = hashlib.sha256((ROOT / 'config/local-rag-artifacts.json').read_bytes() + (ROOT / 'scripts/local-model-runtime.py').read_bytes()).hexdigest()
judge = LocalJudge(args.llm_origin, args.model, ROOT / 'work/evaluation/judge-cache', identity)
embedding = LocalEmbeddings(args.embedding_origin)
scorers = metrics(judge, embedding, args.strictness)
source_hash = None
if args.controls:
    samples = [
        {'id':'C01','question':'課程在哪間教室？','referenceAnswer':'課程在 B203 教室。','response':'課程在 B203 教室。','contexts':['課程地點是 B203 教室。'], 'expectedFaithfulness':1, 'expectedContextPrecision':1},
        {'id':'C02','question':'課程在哪間教室？','referenceAnswer':'課程在 B203 教室。','response':'課程在 B204 教室。','contexts':['課程地點是 B203 教室。'], 'expectedFaithfulness':0, 'expectedContextPrecision':1},
        {'id':'C03','question':'課程在哪間教室？','referenceAnswer':'課程在 B203 教室。','response':'課程在 B203 教室。','contexts':['客服於平日上午九點至下午六點服務。'], 'expectedFaithfulness':0, 'expectedContextPrecision':0},
        {'id':'C04','question':'課程在哪間教室？','referenceAnswer':'課程在 B203 教室。','response':'客服於平日上午九點至下午六點服務。','contexts':['課程地點是 B203 教室。客服於平日上午九點至下午六點服務。'], 'expectedFaithfulness':1, 'expectedContextPrecision':1},
        {'id':'C05','question':'課程在哪間教室，幾點開始？','referenceAnswer':'課程在 B203 教室，上午十點開始。','response':'課程在 B203 教室，上午十點開始。',
         'contexts':['課程地點是 B203 教室。','課程上午十點開始。'], 'expectedFaithfulness':1,'expectedContextPrecision':1}]
else:
    raw = pathlib.Path(args.artifact).read_bytes()
    source_hash = hashlib.sha256(raw).hexdigest()
    source = json.loads(raw)
    samples = []
    selected = set(args.ids.split(',')) if args.ids else None
    for item in source['results']:
        if selected is not None and item['id'] not in selected:
            continue
        answer = item.get('answer')
        text = '\n'.join(s['text'] for s in answer.get('generated', [])) if answer else ''
        samples.append({'id':item['id'],'question':item['question'],'referenceAnswer':item['referenceAnswer'],
                        'response':text,'contexts':[s['excerpt'] for s in answer['sources']] if answer else [],
                        'status':item['actualStatus'],'split':item['split']})
results = []

async def run():
    for sample in samples:
        row = {**sample, 'scores':{}, 'errors':{}, 'traces':[]}
        start = len(judge.traces)
        if not sample['response'] or not sample['contexts'] or not sample['referenceAnswer']:
            row['skippedReason'] = 'NO_GENERATED_ANSWER_OR_REFERENCE_CONTEXT; refusal/access control evaluated separately'
            row['scores'] = {name: None for name in scorers}
        else:
            arguments = {
                'contextPrecision': {'user_input': sample['question'], 'reference': sample['referenceAnswer'], 'retrieved_contexts': sample['contexts']},
                'faithfulness': {'user_input': sample['question'], 'response': sample['response'], 'retrieved_contexts': sample['contexts']},
                'answerRelevance': {'user_input': sample['question'], 'response': sample['response']}}
            for name, scorer in scorers.items():
                try:
                    result = await scorer.ascore(**arguments[name])
                    score = float(result.value)
                    row['scores'][name] = score if math.isfinite(score) else None
                    if not math.isfinite(score):
                        row['errors'][name] = 'NON_FINITE_SCORE'
                except Exception as error:
                    row['scores'][name] = None
                    row['errors'][name] = str(error)[:240]
                print(sample['id'], name, row['scores'][name], flush=True)
        row['traces'] = judge.traces[start:]
        if args.controls:
            row['controlPass'] = all(row['scores'][metric] is not None and abs(row['scores'][metric] - sample[key]) < 1e-6 for metric,key in [('faithfulness','expectedFaithfulness'),('contextPrecision','expectedContextPrecision')])
        results.append(row)
        journal.write(json.dumps(row, ensure_ascii=False) + '\n'); journal.flush()

try:
    asyncio.run(run())
finally:
    journal.close()
    averages = {}
    for name in scorers:
        values = [r['scores'][name] for r in results if r['scores'].get(name) is not None]
        averages[name] = {'mean':statistics.mean(values) if values else None, 'scored':len(values), 'requested':len(results)}
    usage = [trace['usage'] for trace in judge.traces if trace['usage']]
    controls = None
    if args.controls:
        controls = {'faithfulnessAndPrecisionPasses':sum(r.get('controlPass',False) for r in results), 'samples':len(results),
                    'relevanceOrdersOnTopicAboveOffTopic':len(results)>=4 and results[0]['scores']['answerRelevance'] is not None and results[3]['scores']['answerRelevance'] is not None and results[0]['scores']['answerRelevance'] > results[3]['scores']['answerRelevance']}
    report = {'measuredAt':datetime.datetime.now(datetime.timezone.utc).isoformat(), 'ragasVersion':version('ragas'),
        'judgeModel':args.model,'embeddingModel':'BGE-M3','localOnly':True,'telemetryDisabled':True,'sourceArtifactSha256':source_hash,
        'adapterSha256':ADAPTER_SHA256,
        'configuration':{'shortChinesePrompts':True,'fewShotExamples':0,'relevanceStrictness':args.strictness,'temperature':0,'judgeMaxTokens':768,'contextPrecisionPromptVersion':'partial-evidence-v2'},
        'selection':{'requestedSamples':len(samples),'completedSamples':len(results),'requestedIds':[sample['id'] for sample in samples],'complete':len(samples)==len(results)},
        'totals':{'averages':averages,'judgeRequests':sum(not t['cacheHit'] for t in judge.traces), 'judgeCacheHits':sum(t['cacheHit'] for t in judge.traces),
                  'judgeInputTokens':sum(u.get('prompt_tokens',0) for u in usage),'judgeOutputTokens':sum(u.get('completion_tokens',0) for u in usage),'paidModelApiCost':0},
        'controls':controls,'results':results,'limitations':['Small local judge may be inaccurate; scores are not independent human judgments',
            'Generator and judge share a model; correlated bias is possible','Short Chinese prompts and strictness=1 differ from default Ragas configuration',
            'Missing/refused/unauthorized answers are not assigned perfect semantic scores','Errors and non-finite scores remain null and excluded from means with coverage reported']}
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'output':str(output),'totals':report['totals'],'controls':controls}),flush=True)
