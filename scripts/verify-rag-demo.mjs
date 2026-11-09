// Real HTTP + real browser verification; no model or API stubs. Does not mutate documents.
import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chromium, expect } from '../frontend/node_modules/@playwright/test/index.mjs'

const root = path.resolve(new URL('..', import.meta.url).pathname)
process.chdir(root)
const base = process.env.AKA_DEMO_BASE ?? fs.readFileSync('work/public-url.txt', 'utf8').trim()
const origin = new URL(base)
const external = !['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname)
if (external && (origin.protocol !== 'https:' || !process.env.HTTPS_PROXY)) throw new Error('External verification requires HTTPS and the inherited proxy')
const stamp = Date.now()
fs.mkdirSync('work/screenshots/rag', { recursive: true })
function http(route, data) {
  const args = ['-fSs', '--max-time', '30', '-w', '\n%{http_code}', base + route]
  if (data) args.push('-H', 'Content-Type: application/json', '--data', JSON.stringify(data))
  const raw = execFileSync('curl', args, { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 })
  const boundary = raw.lastIndexOf('\n')
  return { status: Number(raw.slice(boundary + 1)), body: JSON.parse(raw.slice(0, boundary)) }
}
function request(route, data) { return http(route, data).body }
const transports = []
async function ask(question) {
  const created = http('/api/question-jobs', { question })
  assert.equal(created.status, 202)
  assert.equal(created.body.status, 'RUNNING')
  assert.equal(created.body.answer, null)
  const deadline = Date.now() + 300000
  let polls = 0
  while (Date.now() < deadline) {
    const response = http(`/api/question-jobs/${created.body.id}`)
    assert.equal(response.status, 200); polls++
    if (response.body.status === 'FAILED') throw new Error(JSON.stringify(response.body.error))
    if (response.body.status === 'COMPLETE') {
      transports.push({ question, creationHttpStatus: created.status, pollHttpStatus: response.status, polls, elapsedMs: response.body.elapsedMs })
      return response.body.answer
    }
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  throw new Error('Question job exceeded five-minute verification deadline')
}
function verify(answer) {
  assert.equal(answer.mode, 'LOCAL_RAG_GENERATION')
  assert.equal(answer.retrieval, 'HYBRID_BM25_DENSE')
  let sourceChecks = 0, citationChecks = 0
  const sources = new Map()
  for (const source of answer.sources) {
    assert.equal(source.href, `/api/documents/${source.documentId}/chunks/${source.chunkId}?version=${source.version}`)
    const canonical = request(source.href)
    for (const key of ['documentId', 'chunkId', 'version', 'heading', 'excerpt', 'lineStart', 'lineEnd']) assert.equal(canonical[key], source[key])
    sources.set(source.chunkId, canonical)
    sourceChecks++
  }
  for (const statement of answer.generated) for (const id of statement.chunkIds) {
    assert.ok(sources.has(id)); citationChecks++
  }
  for (const segment of answer.segments) assert.ok(sources.get(segment.chunkId)?.excerpt.includes(segment.quote))
  return { sourceChecks, citationChecks }
}
const status = request('/api/status')
assert.equal(status.status, 'READY')
assert.equal(status.mode, 'LOCAL_RAG_GENERATION')
assert.equal(status.embedding, 'BGE_M3')
assert.equal(status.rag.llm, process.env.AKA_EXPECTED_MODEL ?? 'qwen3-4b-instruct-2507')
const answer = await ask('你用什麼架構')
assert.equal(answer.status, 'ANSWER_GENERATED')
assert.deepEqual(answer.configuration, status.configuration)
const generated = answer.generated.map(s => s.text).join('\n')
for (const term of ['Java', 'Spring Boot', 'Vue']) assert.ok(generated.includes(term))
assert.ok(answer.modelInputTokens > 0 && answer.modelOutputTokens > 0)
assert.ok(answer.pipeline.some(s => s.name === 'citation_validation' && s.status === 'COMPLETE'))
const apiChecks = verify(answer)
const noEvidence = await ask('員工的薪水是多少？')
assert.equal(noEvidence.status, 'INSUFFICIENT_EVIDENCE')
assert.deepEqual(noEvidence.generated, [])
assert.deepEqual(noEvidence.segments, [])
const roleAnswer = await ask('我在瀏覽器填 ADMIN 就能看到 HR 文件嗎？')
const roleChecks = verify(roleAnswer)
const roleExplanationComplete = roleAnswer.status === 'ANSWER_GENERATED' && /不|無法|不能/.test(roleAnswer.generated.map(s => s.text).join('\n'))

const browser = await chromium.launch({ executablePath: process.env.AKA_CHROMIUM_PATH ?? '/usr/bin/chromium',
  headless: true, ...(external ? { proxy: { server: process.env.HTTPS_PROXY } } : {}) })
let evidence
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = [], dialogs = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.dismiss() })
  const response = await page.goto(base, { waitUntil: 'networkidle', timeout: 60000 })
  assert.equal(response.status(), 200)
  await expect(page.getByText('本地 LLM / 混合檢索', { exact: true })).toBeVisible()
  await expect(page.getByText('從一個問題開始', { exact: true })).toBeVisible()
  const questionResponse = page.waitForResponse(r => r.url().endsWith('/api/question-jobs') && r.request().method() === 'POST', { timeout: 30000 })
  const completedResponse = page.waitForResponse(async r => {
    if (!r.url().includes('/api/question-jobs/') || r.request().method() !== 'GET' || r.status() !== 200) return false
    return (await r.json()).status === 'COMPLETE'
  }, { timeout: 300000 })
  completedResponse.catch(() => {})
  await page.getByRole('button', { name: /架構與技術/ }).click()
  const posted = await questionResponse
  assert.equal(posted.status(), 202)
  const postedJob = await posted.json()
  assert.equal(postedJob.status, 'RUNNING'); assert.equal(postedJob.answer, null)
  const completed = await completedResponse
  const browserJob = await completed.json()
  assert.equal(browserJob.id, postedJob.id)
  const browserAnswer = browserJob.answer
  assert.equal(browserAnswer.status, 'ANSWER_GENERATED')
  const browserChecks = verify(browserAnswer)
  await expect(page.getByText('模型回答 · 附來源', { exact: true })).toBeVisible()
  await expect(page.locator('.generated-answer')).toContainText('Spring Boot')
  await page.locator('.generated-answer .citation').first().click()
  await page.getByRole('button', { name: /核對目前來源版本/ }).click()
  await expect(page.getByText('已核對：目前文件版本與引用原文一致。', { exact: true })).toBeVisible({ timeout: 30000 })
  await page.locator('.pipeline-detail summary').click()
  await expect(page.locator('.pipeline-detail')).toContainText('付費 API $0')
  await expect(page.locator('.pipeline-detail')).toContainText('引用核對')
  if (status.rag.llm.includes('qwen3')) await expect(page.locator('.pipeline-detail')).toContainText('Qwen3 4B')
  const desktop = `work/screenshots/rag/${stamp}-answer.png`
  await page.screenshot({ path: desktop, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  const mobile = `work/screenshots/rag/${stamp}-mobile.png`
  await page.screenshot({ path: mobile, fullPage: true })
  assert.deepEqual(errors, []); assert.deepEqual(dialogs, [])
  evidence = { checkedAt: new Date().toISOString(), base, external, pageHttpStatus: 200,
    mode: status.mode, configuration: status.configuration, apiQuestion: '你用什麼架構', apiStatus: answer.status,
    transport: 'HTTP_202_JOB_POLLING', transports, apiRequestId: answer.requestId, apiGenerated: answer.generated, apiChecks,
    noEvidenceStatus: noEvidence.status, roleExplanationStatus: roleAnswer.status, roleExplanationGenerated: roleAnswer.generated,
    roleExplanationChecks: roleChecks, roleExplanationComplete, browserJobCreationHttpStatus: posted.status(), browserQuestionStatus: browserAnswer.status,
    browserRequestId: browserAnswer.requestId, browserChecks, sourceButtonVerification: 'PASS',
    traceAndTokenDisplay: 'PASS', mobileOverflow: false, pageErrors: errors, unexpectedDialogs: dialogs,
    screenshots: { desktop, mobile }, paidModelApiCost: 0, limitations: ['Citation provenance does not prove sentence entailment', 'Temporary tunnel depends on the environment and processes'] }
} finally { await browser.close() }
const output = `work/rag-demo-verification-${stamp}.json`
fs.writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n', { flag: 'wx' })
console.log(JSON.stringify({ result: 'PASS', output, ...evidence }, null, 2))
