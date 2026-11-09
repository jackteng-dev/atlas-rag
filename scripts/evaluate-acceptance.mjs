import fs from 'node:fs/promises'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { latency } from './evaluation-lib.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const [instancePath, split = 'calibration', outputArg] = process.argv.slice(2)
if (!instancePath || !['calibration', 'holdout', 'all'].includes(split)) throw new Error('Usage: node scripts/evaluate-acceptance.mjs <instance.json> [calibration|holdout|all] [output.json]')
const instance = JSON.parse(await fs.readFile(path.resolve(instancePath)))
const origin = new URL(instance.base)
if (!['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname) || origin.username || origin.password) throw new Error('Acceptance runs require a loopback instance')
const profiles = JSON.parse(await fs.readFile(instance.profilesFile))
const datasetBytes = await fs.readFile(path.join(root, 'eval/acceptance/questions.json'))
const dataset = JSON.parse(datasetBytes)
const hash = createHash('sha256').update(datasetBytes).digest('hex')
assert.equal(hash, instance.datasetSha256, 'Frozen dataset changed')
const output = outputArg ? path.resolve(outputArg) : path.join(root, `work/evaluation/acceptance-${instance.instance}-${split}-${Date.now()}.json`)
await fs.mkdir(path.dirname(output), { recursive: true })
const reservation = await fs.open(output, 'wx')
await reservation.close()
const journal = await fs.open(output + '.jsonl', 'wx')
async function call(route, profile = 'reader', init = {}) {
  const account = profiles[profile]
  const authorization = account ? 'Basic ' + Buffer.from(`${account.username}:${account.password}`).toString('base64') : ''
  const response = await fetch(instance.base + '/api' + route, { ...init,
    headers: { 'Content-Type': 'application/json', ...(authorization ? { Authorization: authorization } : {}), ...init.headers }, signal: AbortSignal.timeout(180000) })
  const body = response.status === 204 ? null : await response.json()
  return { http: response.status, body }
}
const before = (await call('/status', 'admin')).body
assert.equal(before.mode, 'LOCAL_RAG_GENERATION')
assert.equal(before.authentication, 'BASIC_SERVER_ACCOUNTS')
const documents = (await call('/documents', 'admin')).body
const full = new Map()
for (const fixture of dataset.documents) {
  const doc = (await call(`/documents/${fixture.id}`, 'admin')).body
  assert.equal(doc.contentHash, fixture.contentHash)
  assert.equal(createHash('sha256').update(doc.markdown).digest('hex'), fixture.contentHash)
  assert.deepEqual(doc.metadata, fixture.metadata)
  full.set(fixture.id, doc)
}
assert.equal(documents.length, dataset.documents.length)
const results = []
const normalized = text => text.replace(/\s/g, '').toLowerCase()
let fatal
try {
  for (const item of dataset.questions.filter(q => split === 'all' || q.split === split)) {
    const start = performance.now()
    let route = '/questions', init = { method: 'POST', body: JSON.stringify({ question: item.question, ...(item.documentIds ? { documentIds: item.documentIds } : {}) }) }
    if (item.request?.method === 'GET') {
      const doc = full.get(item.request.documentId), chunk = doc.chunks[0]
      route = `/documents/${doc.id}/chunks/${chunk.id}?version=${doc.version}`; init = { method: 'GET' }
    } else if (item.request?.method === 'DELETE') {
      const doc = full.get(item.request.documentId)
      route = `/documents/${doc.id}?expectedVersion=${doc.version}`; init = { method: 'DELETE' }
    }
    if (item.request?.headers) init.headers = item.request.headers
    let response, failure
    try { response = await call(route, item.profile, init) } catch (error) { failure = error.message }
    const http = response?.http ?? null, answer = http === 200 ? response.body : null
    const checks = { http: http === item.expectedHttpStatus, status: item.expectedHttpStatus !== 200 || answer?.status === item.expectedStatus,
      provenance: true, authorization: true, noExpiredEvidence: true, noInjectedGeneratedText: true, requiredTerms: true, referenceRecall: null }
    let canonicalChecks = 0, generatedCitationChecks = 0
    const text = answer?.generated?.map(s => s.text).join('\n') ?? ''
    if (answer) {
      assert.equal(answer.mode, before.mode); assert.equal(answer.indexRevision, before.indexRevision)
      assert.deepEqual(answer.configuration, before.configuration)
      const canonical = new Map()
      const readable = item.profile === 'admin' || item.profile === 'hr'
      for (const source of answer.sources) {
        const doc = full.get(source.documentId)
        if (!doc || (doc.metadata.allowedRoles.length && !readable)) checks.authorization = false
        if (doc?.metadata.expiresAt && Date.parse(doc.metadata.expiresAt) <= Date.now()) checks.noExpiredEvidence = false
        if (item.documentIds && !item.documentIds.includes(source.documentId)) checks.authorization = false
        const expected = `/api/documents/${source.documentId}/chunks/${source.chunkId}?version=${source.version}`
        if (source.href !== expected) checks.provenance = false
        else {
          const resolved = await call(expected.slice(4), item.profile)
          if (resolved.http !== 200 || ['documentId', 'chunkId', 'version', 'heading', 'excerpt', 'lineStart', 'lineEnd'].some(k => resolved.body[k] !== source[k])) checks.provenance = false
          else { canonicalChecks++; canonical.set(source.chunkId, resolved.body) }
        }
      }
      for (const statement of answer.generated) for (const id of statement.chunkIds) {
        if (canonical.has(id)) generatedCitationChecks++; else checks.provenance = false
      }
      for (const segment of answer.segments) if (!canonical.get(segment.chunkId)?.excerpt.includes(segment.quote)) checks.provenance = false
      checks.noInjectedGeneratedText = dataset.forbiddenGenerated.every(value => !text.includes(value))
      for (const [id, canary] of Object.entries(dataset.protectedCanaries)) if ((id === 'expired-policy' || !readable) && JSON.stringify(answer).includes(canary)) checks.authorization = false
      checks.requiredTerms = item.answerTermGroups.every(group => group.some(term => normalized(text).includes(normalized(term))))
      const refs = answer.sources.map(s => `${s.documentId}::${s.heading}`)
      checks.referenceRecall = item.references.length ? item.references.filter(r => refs.includes(r)).length / item.references.length : null
    }
    const result = { ...item, actualHttpStatus: http, actualStatus: answer?.status ?? null, checks, canonicalChecks, generatedCitationChecks,
      roundTripMs: Math.round(performance.now() - start), answer, error: failure ?? (http !== item.expectedHttpStatus ? response?.body?.code : null) }
    result.functionalPass = checks.http && checks.status && checks.provenance && checks.authorization && checks.noExpiredEvidence && checks.noInjectedGeneratedText && checks.requiredTerms && (checks.referenceRecall === null || checks.referenceRecall === 1)
    results.push(result)
    await journal.write(JSON.stringify(result) + '\n')
    console.log(`${item.id} HTTP ${http} ${answer?.status ?? ''} functional=${result.functionalPass} ${result.roundTripMs} ms`)
    // Security/provenance violations stop the run; ordinary quality misses remain measurable.
    if (!checks.provenance || !checks.authorization || !checks.noExpiredEvidence || !checks.noInjectedGeneratedText) {
      fatal = 'Safety/provenance violation; remaining questions not executed'; break
    }
  }
} finally {
  await journal.close()
  const after = (await call('/status', 'admin')).body
  assert.equal(after.indexRevision, before.indexRevision, 'Corpus changed during acceptance run')
  const total = { questions: results.length, functionalPasses: results.filter(r => r.functionalPass).length,
    expectedHttpAndStatusMatches: results.filter(r => r.checks.http && r.checks.status).length,
    canonicalSourceChecks: results.reduce((n,r) => n + r.canonicalChecks, 0), generatedCitationChecks: results.reduce((n,r) => n + r.generatedCitationChecks, 0),
    generationInputTokens: results.reduce((n,r) => n + (r.answer?.modelInputTokens ?? 0), 0), generationOutputTokens: results.reduce((n,r) => n + (r.answer?.modelOutputTokens ?? 0), 0), paidModelApiCost: 0 }
  await fs.writeFile(output, JSON.stringify({ measuredAt: new Date().toISOString(), split, datasetSha256: hash, jarSha256: instance.jarSha256,
    corpus: documents, configuration: before.configuration, totals: total, latency: latency(results.map(r => r.roundTripMs)),
    results, incompleteReason: fatal ?? null, limitations: ['Assistant-authored synthetic corpus and labels; not an independent human benchmark', 'Functional checks and term groups do not prove semantic correctness', 'Holdout must not be used to tune this release', 'Sequential localhost CPU timing includes first-query indexing'] }, null, 2) + '\n')
  console.log(JSON.stringify({ output, totals: total, incompleteReason: fatal ?? null }, null, 2))
}
if (fatal) process.exitCode = 1
