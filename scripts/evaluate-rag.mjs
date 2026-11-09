import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { latency } from './evaluation-lib.mjs'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const base = process.env.AKA_EVAL_BASE ?? 'http://127.0.0.1:18081'
const hostname = new URL(base).hostname
if (process.env.HTTPS_PROXY && !['localhost', '127.0.0.1', '[::1]'].includes(hostname) && process.env.NODE_USE_ENV_PROXY !== '1') throw new Error('Set NODE_USE_ENV_PROXY=1 for external HTTPS evaluation')
async function call(path, init) {
  const response = await fetch(base + '/api' + path, { ...init, signal: AbortSignal.timeout(150000) })
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`)
  return response.json()
}
const datasetBytes = await readFile(`${root}/eval/rag-questions.json`)
const dataset = JSON.parse(datasetBytes)
const before = await call('/status')
if (before.mode !== 'LOCAL_RAG_GENERATION' || before.embedding !== 'BGE_M3') throw new Error('Evaluation requires the explicit local RAG profile')
const docs = await call('/documents')
const corpus = await Promise.all(docs.map(async doc => {
  const content = await call(`/documents/${doc.id}`)
  const hash = createHash('sha256').update(content.markdown).digest('hex')
  if (hash !== doc.contentHash) throw new Error('Corpus hash mismatch')
  return { id: doc.id, version: doc.version, contentHash: hash, metadata: doc.metadata, chunkCount: doc.chunkCount }
}))
const results = []
for (const item of dataset.questions) {
  const started = performance.now()
  const answer = await call('/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ question: item.question }) })
  const roundTripMs = Math.round(performance.now() - started)
  if (answer.mode !== before.mode || answer.indexRevision !== before.indexRevision) throw new Error('Mode or index changed during evaluation')
  assert.deepEqual(answer.configuration, before.configuration, 'Configuration changed during evaluation')
  let validCitations = 0, citationCount = 0
  const canonical = new Map()
  for (const source of answer.sources) {
    const expected = `/api/documents/${source.documentId}/chunks/${source.chunkId}?version=${source.version}`
    if (source.href !== expected) throw new Error('Non-canonical citation URL')
    const current = await call(source.href.slice(4))
    if (['documentId','chunkId','version','heading','excerpt','lineStart','lineEnd'].some(key => current[key] !== source[key])) throw new Error('Source provenance differs')
    canonical.set(source.chunkId, current)
  }
  for (const statement of answer.generated) for (const id of statement.chunkIds) {
    citationCount++
    if (canonical.has(id)) validCitations++
  }
  for (const segment of answer.segments) if (!canonical.get(segment.chunkId)?.excerpt.includes(segment.quote)) throw new Error('Extract is not source text')
  const refs = answer.sources.map(s => `${s.documentId}::${s.heading}`)
  const text = answer.generated.map(s => s.text).join('\n')
  const labeledHits = refs.filter(ref => item.references.includes(ref)).length
  results.push({ ...item, actualStatus: answer.status, statusPass: answer.status === item.expectedStatus,
    referenceHits: item.references.filter(ref => refs.includes(ref)).length, selectedContextPrecisionByHeadingLabel: item.references.length && refs.length ? labeledHits / refs.length : null,
    requiredTermsPresent: item.requiredTerms.every(term => text.toLowerCase().includes(term.toLowerCase())),
    citationCount, validCitations, roundTripMs, answer })
  console.log(`${item.id} ${answer.status} citations=${validCitations}/${citationCount} ${roundTripMs} ms`)
}
const after = await call('/status')
if (after.indexRevision !== before.indexRevision) throw new Error('Corpus changed during evaluation')
const totals = { questions: results.length, expectedStatusMatches: results.filter(r => r.statusPass).length,
  citedSourceProvenanceChecks: results.reduce((n,r)=>n+r.validCitations,0), generatedCitationReferences: results.reduce((n,r)=>n+r.citationCount,0),
  faithfulness: null, answerRelevance: null, paidModelApiCost: 0,
  generationInputTokens: results.reduce((n,r)=>n+(r.answer.modelInputTokens ?? 0),0), generationOutputTokens: results.reduce((n,r)=>n+(r.answer.modelOutputTokens ?? 0),0) }
const output = process.argv[2] ? resolve(process.argv[2]) : `${root}/work/evaluation/rag-${Date.now()}.json`
await mkdir(dirname(output), { recursive: true })
await writeFile(output, JSON.stringify({ measuredAt: new Date().toISOString(), datasetSha256: createHash('sha256').update(datasetBytes).digest('hex'),
  configuration: before.configuration, corpus, totals, latency: latency(results.map(r=>r.roundTripMs)), results,
  limitations: ['Project-authored eight questions; no independent holdout', 'Heading labels are not sentence entailment labels', 'Citation provenance does not measure faithfulness', 'Faithfulness and answer relevance require separate review labels', 'Sequential local CPU measurements; not a load benchmark'] }, null, 2) + '\n', { flag: 'wx' })
console.log(JSON.stringify({ output, totals }, null, 2))
