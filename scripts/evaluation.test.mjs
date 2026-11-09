import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { totals, compare, latency, measure, checkpoint, sha256 } from './evaluation-lib.mjs'
const baseRow = { id:'Q', expectedStatus:'EXTRACTS_FOUND', actualStatus:'EXTRACTS_FOUND',statusPass:true,
  referenceCount:2,referenceHits:2,selectedReferenceHits:2,allReferencesFound:true,coverage:.6,citationCount:2,validCitations:2 }
test('gate-only status improvement cannot conceal lost selected evidence or missing candidate recall', () => {
  const refused = {...baseRow,actualStatus:'INSUFFICIENT_EVIDENCE',statusPass:false,selectedReferenceHits:0}
  assert.deepEqual(compare([baseRow],[refused]).map(d=>[d.statusDelta,d.referenceDelta,d.selectedReferenceDelta]),[[-1,0,-2]])
  const synonym = {...baseRow,referenceCount:1,referenceHits:0,selectedReferenceHits:0,allReferencesFound:false}
  assert.equal(totals([synonym]).referenceHits,0)
  assert.equal(totals([synonym]).statusPass,1)
  assert.equal(totals([refused]).falseRefusals,1)
  const falseAccept = {...baseRow,expectedStatus:'INSUFFICIENT_EVIDENCE',statusPass:false,referenceCount:0}
  assert.equal(totals([falseAccept]).falseAccepts,1)
})
test('latency uses median and nearest-rank p95 over all measured samples',()=> {
  assert.deepEqual(latency([4,1,3,2]),{samples:4,medianMs:2.5,p95Ms:4})
})
test('API measurement rejects effective-setting drift and forged citation provenance',async()=> {
  const configuration={chunkMaxChars:800,topK:5,coverageThreshold:.6}
  const source={chunkId:'doc-123',documentId:'doc',version:1,heading:'H',lineStart:2,lineEnd:2,excerpt:'evidence',href:'/api/documents/doc/chunks/doc-123?version=1'}
  let drift=false, forge=false, mode='OFFLINE_EXTRACTIVE'
  const server=createServer((request,response)=> {
    response.setHeader('Content-Type','application/json')
    if (request.url==='/api/status') return response.end(JSON.stringify({mode,embedding:'NONE',retrieval:'BM25_KEYWORD',configuration}))
    if (request.url==='/api/documents') return response.end(JSON.stringify([{id:'doc',version:1,contentHash:sha256('text'),chunkCount:1}]))
    if (request.url==='/api/documents/doc') return response.end(JSON.stringify({markdown:'text'}))
    if (request.url.startsWith('/api/documents/doc/chunks/')) return response.end(JSON.stringify({...source,documentId:forge?'wrong':source.documentId}))
    response.end(JSON.stringify({mode,indexRevision:4,configuration:{...configuration,topK:drift?1:5},
      status:'EXTRACTS_FOUND',sources:[source],segments:[{chunkId:source.chunkId,quote:'evidence'}],coverage:1,missingTerms:[],elapsedMs:1}))
  }).listen(0,'127.0.0.1')
  await once(server,'listening')
  const base=`http://127.0.0.1:${server.address().port}`
  const item={id:'Q',question:'evidence',expectedStatus:'EXTRACTS_FOUND',references:['doc::H']}
  try {
    assert.equal((await measure(base,item,configuration,4)).validCitations,1)
    forge=true
    assert.equal((await measure(base,item,configuration,4)).validCitations,0)
    drift=true
    await assert.rejects(measure(base,item,configuration,4),/configuration/)
    mode='OLLAMA_LOCAL_EXCERPT_SELECTOR'
    await assert.rejects(checkpoint(base,[]),/offline/)
    mode='OFFLINE_EXTRACTIVE'
    await assert.rejects(checkpoint(base,[{id:'doc',version:2,contentHash:sha256('text')}]),/Corpus/)
  } finally { server.close(); await once(server,'close') }
})
