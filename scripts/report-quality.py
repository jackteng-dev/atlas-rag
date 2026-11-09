#!/usr/bin/env python3
"""Combine frozen calibration/holdout evidence and create an explicit human review worksheet."""
import argparse
import csv
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('calibration')
parser.add_argument('holdout')
parser.add_argument('--output', required=True)
args = parser.parse_args()
paths = [pathlib.Path(args.calibration), pathlib.Path(args.holdout)]
reports = [json.loads(p.read_text()) for p in paths]
assert reports[0]['split']=='calibration' and reports[1]['split']=='holdout'
assert all(not report['incompleteReason'] for report in reports)
for key in ['datasetSha256','jarSha256','configuration']:
    assert reports[0][key]==reports[1][key], 'Candidate changed between splits'
assert [(d['id'],d['contentHash'],d['metadata']) for d in reports[0]['corpus']]==[(d['id'],d['contentHash'],d['metadata']) for d in reports[1]['corpus']]
rows = [row for report in reports for row in report['results']]
assert len({row['id'] for row in rows})==len(rows)==40
totals = {key:sum(report['totals'][key] for report in reports) for key in reports[0]['totals']}
output = pathlib.Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
report = {'measuredAt': reports[1]['measuredAt'], 'split':'calibration+holdout', 'datasetSha256':reports[0]['datasetSha256'],
    'jarSha256':reports[0]['jarSha256'], 'configuration':reports[0]['configuration'], 'corpus':reports[0]['corpus'],
    'sourceArtifacts':[{'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for path in paths],
    'totals':totals,'results':rows,'limitations':['Synthetic assistant-authored corpus/labels, not independently human labeled',
        'Term groups and heading labels do not prove semantic quality','Holdout ran once after candidate was fixed and was not used for tuning',
        'Shared CPU/model runtime; calibration used batch 2048/512, holdout used bounded batch 128/128; timings are not a controlled performance comparison',
        'Human review worksheet is pending; no human sign-off is claimed']}
with output.open('x') as file:json.dump(report,file,ensure_ascii=False,indent=2);file.write('\n')
worksheet = output.with_suffix('.review.csv')
with worksheet.open('x',newline='') as file:
    fields=['id','split','question','functionalPass','generatedAnswer','referenceAnswer','context','humanFaithfulness','humanRelevance','humanNotes']
    writer=csv.DictWriter(file,fieldnames=fields);writer.writeheader()
    for row in rows:
        answer=row['answer']
        writer.writerow({key:row[key] for key in ['id','split','question','functionalPass']} | {
            'generatedAnswer':'\n'.join(s['text'] for s in answer['generated']) if answer else '',
            'referenceAnswer':row['referenceAnswer'],
            'context':'\n\n'.join(f"{s['documentId']} v{s['version']} / {s['heading']}\n{s['excerpt']}" for s in answer['sources']) if answer else '',
            'humanFaithfulness':'PENDING_HUMAN_REVIEW','humanRelevance':'PENDING_HUMAN_REVIEW','humanNotes':''})
print(json.dumps({'output':str(output),'reviewWorksheet':str(worksheet),'totals':totals}))
