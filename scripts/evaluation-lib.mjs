import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')
export async function get(base, path, init) {
  const response = await fetch(base + path, { ...init, signal: AbortSignal.timeout(15000) })
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`)
  return response.json()
}
export async function inputs(root) {
  const bytes = await readFile(`${root}/eval/questions.json`)
  const dataset = JSON.parse(bytes)
  const historical = JSON.parse(await readFile(`${root}/eval/results.json`))
  if (sha256(bytes) !== historical.datasetSha256) throw new Error('Fixed dataset differs from preserved baseline')
  const corpus = await Promise.all(historical.corpus.map(async d => {
    if (sha256(await readFile(`${root}/backend/src/main/resources/corpus/${d.id}.md`)) !== d.contentHash)
      throw new Error(`Bundled corpus differs from preserved baseline: ${d.id}`)
    return { id: d.id, version: d.version, contentHash: d.contentHash }
  }))
  return { dataset, datasetSha256: sha256(bytes), corpus, historical }
}
export async function checkpoint(base, corpus, expectedConfiguration) {
  const status = await get(base, '/api/status')
  if (status.mode !== 'OFFLINE_EXTRACTIVE' || status.embedding !== 'NONE' || status.retrieval !== 'BM25_KEYWORD')
    throw new Error('Evaluation requires offline BM25 / no embeddings')
  if (expectedConfiguration && Object.entries(expectedConfiguration).some(([k,v]) => status.configuration[k] !== v))
    throw new Error('Server configuration differs from requested settings')
  const docs = await get(base, '/api/documents')
  if (docs.length !== corpus.length || corpus.some(c => !docs.some(d => d.id === c.id && d.version === c.version && d.contentHash === c.contentHash)))
    throw new Error('Corpus/version mismatch; use a fresh separate store')
  // Verify actual Markdown bytes too, rather than trusting stored hash metadata alone.
  for (const doc of docs) {
    const body = await get(base, `/api/documents/${doc.id}`)
    if (sha256(body.markdown) !== doc.contentHash) throw new Error(`Corpus content mismatch: ${doc.id}`)
  }
  return { status, corpus: docs.map(({id,version,contentHash,chunkCount}) => ({id,version,contentHash,chunkCount})) }
}
export async function measure(base, item, configuration, revision) {
  const started = performance.now()
  const answer = await get(base, '/api/questions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question: item.question, topK: configuration.topK }) })
  const roundTripMs = Number((performance.now() - started).toFixed(3))
  if (answer.mode !== 'OFFLINE_EXTRACTIVE' || answer.indexRevision !== revision ||
      Object.entries(configuration).some(([k,v]) => answer.configuration?.[k] !== v))
    throw new Error('Answer configuration/mode/index changed during evaluation')
  const retrieved = answer.sources.map(s => `${s.documentId}::${s.heading}`)
  let validCitations = 0
  for (const segment of answer.segments) {
    const source = answer.sources.find(s => s.chunkId === segment.chunkId)
    if (!source || !source.excerpt.includes(segment.quote)) continue
    const canonical = `/api/documents/${source.documentId}/chunks/${source.chunkId}?version=${source.version}`
    if (source.href !== canonical) continue
    const resolved = await get(base, canonical)
    if (resolved.chunkId === source.chunkId && resolved.documentId === source.documentId &&
        resolved.heading === source.heading && resolved.version === source.version &&
        resolved.lineStart === source.lineStart && resolved.lineEnd === source.lineEnd && resolved.excerpt === source.excerpt) validCitations++
  }
  const referenceHits = item.references.filter(ref => retrieved.includes(ref)).length
  const selectedRefs = answer.segments.map(seg => {
    const source = answer.sources.find(s => s.chunkId === seg.chunkId)
    return source && `${source.documentId}::${source.heading}`
  })
  return { ...item, actualStatus: answer.status, statusPass: answer.status === item.expectedStatus,
    referenceHits, referenceCount: item.references.length, allReferencesFound: referenceHits === item.references.length,
    selectedReferenceHits: item.references.filter(ref => selectedRefs.includes(ref)).length,
    retrieved, coverage: answer.coverage, missingTerms: answer.missingTerms, citationCount: answer.segments.length, validCitations,
    backendMs: answer.elapsedMs, roundTripMs, requestId: answer.requestId, sources: answer.sources, segments: answer.segments }
}
export function totals(results) {
  const answerable = results.filter(r => r.referenceCount > 0)
  const unanswerable = results.filter(r => r.expectedStatus === 'INSUFFICIENT_EVIDENCE')
  return { questions: results.length, statusPass: results.filter(r=>r.statusPass).length,
    referenceHits: answerable.reduce((n,r)=>n+r.referenceHits,0), referenceCount: answerable.reduce((n,r)=>n+r.referenceCount,0),
    selectedReferenceHits: answerable.reduce((n,r)=>n+r.selectedReferenceHits,0),
    allReferencesFound: answerable.filter(r=>r.allReferencesFound).length, answerableQuestions: answerable.length,
    correctRefusals: unanswerable.filter(r=>r.statusPass).length, unanswerableQuestions: unanswerable.length,
    falseAccepts: unanswerable.filter(r=>!r.statusPass).length,
    falseRefusals: answerable.filter(r=>r.actualStatus === 'INSUFFICIENT_EVIDENCE').length,
    validCitations: results.reduce((n,r)=>n+r.validCitations,0), citationCount: results.reduce((n,r)=>n+r.citationCount,0) }
}
export function latency(values) {
  const sorted = [...values].sort((a,b)=>a-b)
  const middle = Math.floor(sorted.length/2)
  return { samples: sorted.length, medianMs: sorted.length % 2 ? sorted[middle] : (sorted[middle-1]+sorted[middle])/2,
    p95Ms: sorted[Math.ceil(sorted.length*.95)-1] }
}
export function signature(r) {
  return JSON.stringify([r.actualStatus, r.coverage, r.missingTerms, r.sources, r.segments, r.validCitations])
}
export function compare(baseline, candidate) {
  return candidate.map(r => {
    const b = baseline.find(b=>b.id===r.id)
    const statusDelta = Number(r.statusPass)-Number(b.statusPass)
    const referenceDelta = r.referenceHits-b.referenceHits
    const selectedReferenceDelta = r.selectedReferenceHits-b.selectedReferenceHits
    return { id:r.id, statusDelta, referenceDelta, selectedReferenceDelta,
      baselineStatus:b.actualStatus, actualStatus:r.actualStatus,
      baselineCoverage:b.coverage, coverage:r.coverage }
  }).filter(d=>d.statusDelta || d.referenceDelta || d.selectedReferenceDelta)
}
