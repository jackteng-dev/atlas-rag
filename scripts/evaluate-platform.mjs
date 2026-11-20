// Runs the independent platform evaluation set (eval/platform-v1) against an ISOLATED Atlas platform instance with real
// local models, and writes eval/platform-v1-results.json. It never touches the frozen legacy benchmark files.
// Usage: ATLAS_URL=... ATLAS_ADMIN_EMAIL=... ATLAS_ADMIN_PASSWORD=... node scripts/evaluate-platform.mjs [--quality]
import { readFileSync, writeFileSync } from 'node:fs'
const base = process.env.ATLAS_URL ?? 'http://127.0.0.1:18081'
const quality = process.argv.includes('--quality')
const set = JSON.parse(readFileSync('eval/platform-v1/questions.json', 'utf8'))
const sleep = ms => new Promise(r => setTimeout(r, ms))

/** Minimal single-font text PDF (one text line per page; null → empty page without a text layer). */
function pdf(pages) {
  const objs = []; const add = s => { objs.push(s); return objs.length }
  const font = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
  const kids = []; const pagesId = objs.length + 1 + pages.length * 2 + 0
  const pageIds = []
  for (const text of pages) {
    const content = text == null ? '' : `BT /F1 12 Tf 72 720 Td (${text.replace(/[()\\]/g, m => '\\' + m)}) Tj ET`
    const c = add(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`)
    pageIds.push(add(`<< /Type /Page /Parent PAGES 0 R /MediaBox [0 0 612 792] /Contents ${c} 0 R /Resources << /Font << /F1 ${font} 0 R >> >> >>`))
  }
  const pagesObj = add(`<< /Type /Pages /Kids [${pageIds.map(i => i + ' 0 R').join(' ')}] /Count ${pageIds.length} >>`)
  const catalog = add(`<< /Type /Catalog /Pages ${pagesObj} 0 R >>`)
  let out = '%PDF-1.4\n'; const offsets = []
  objs.forEach((o, i) => { offsets.push(out.length); out += `${i + 1} 0 obj\n${o.replace('PAGES', String(pagesObj))}\nendobj\n` })
  const xref = out.length
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n` + offsets.map(o => String(o).padStart(10, '0') + ' 00000 n \n').join('')
  out += `trailer\n<< /Size ${objs.length + 1} /Root ${catalog} 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  void kids; void pagesId
  return new Blob([Buffer.from(out, 'latin1')], { type: 'application/pdf' })
}

class Client {
  constructor() { this.cookies = new Map() }
  async req(method, path, body, form) {
    if (method !== 'GET' && !this.cookies.has('XSRF-TOKEN')) await this.req('GET', '/api/v2/auth/csrf')
    const headers = { Accept: 'application/json', Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
    if (method !== 'GET') headers['X-XSRF-TOKEN'] = decodeURIComponent(this.cookies.get('XSRF-TOKEN') ?? '')
    if (body) headers['Content-Type'] = 'application/json'
    const res = await fetch(base + path, { method, headers, body: form ?? (body ? JSON.stringify(body) : undefined) })
    for (const c of res.headers.getSetCookie?.() ?? []) { const [p] = c.split(';'); const i = p.indexOf('='); this.cookies.set(p.slice(0, i), p.slice(i + 1)) }
    const text = await res.text()
    if (res.status >= 400) throw new Error(`${method} ${path} ${res.status} ${text.slice(0, 200)}`)
    return text ? JSON.parse(text) : null
  }
}
const admin = new Client()
await admin.req('POST', '/api/v2/auth/login', { email: process.env.ATLAS_ADMIN_EMAIL, password: process.env.ATLAS_ADMIN_PASSWORD })
const stamp = Date.now().toString(36)
const orgs = {}
for (const key of ['A', 'B']) {
  const org = await admin.req('POST', '/api/v2/orgs', { name: `Eval ${key} ${stamp}`, slug: `eval-${key.toLowerCase()}-${stamp}` })
  await admin.req('PUT', `/api/v2/orgs/${org.id}/quota`, { monthlyTokenLimit: 5000000, monthlyRequestLimit: 10000, requestsPerMinute: 500 })
  const ws = (await admin.req('GET', `/api/v2/orgs/${org.id}/workspaces`))[0].id
  const kb = await admin.req('POST', `/api/v2/orgs/${org.id}/knowledge-bases`, { workspaceId: ws, name: 'Eval', description: '', visibility: 'ORG' })
  orgs[key] = { org, kb, docs: {} }
}
for (const d of set.documents) {
  const target = orgs[d.kb]; const form = new FormData(); let name
  if (d.file.startsWith('@pdf:')) { name = d.file.slice(5); form.append('file', pdf(d.pages), name + '.pdf') }
  else if (d.file.startsWith('@md:')) { name = d.file.slice(4); form.append('file', new Blob([d.text]), name + '.md') }
  else { name = d.file.split('/').pop().replace(/\.md$/, ''); form.append('file', new Blob([readFileSync('eval/platform-v1/' + d.file)]), name + '.md') }
  const doc = await admin.req('POST', `/api/v2/orgs/${target.org.id}/knowledge-bases/${target.kb.id}/documents`, null, form)
  let current
  for (;;) { current = await admin.req('GET', `/api/v2/orgs/${target.org.id}/documents/${doc.id}`); if (current.status !== 'PROCESSING') break; await sleep(500) }
  target.docs[name] = { id: doc.id, status: current.status }
}
const A = orgs.A
const results = []
for (const q of set.questions) {
  const conv = await admin.req('POST', `/api/v2/orgs/${A.org.id}/conversations`, { kbIds: [A.kb.id] })
  const t0 = Date.now()
  await admin.req('POST', `/api/v2/orgs/${A.org.id}/conversations/${conv.id}/messages`, { content: q.q, model: quality ? 'quality' : 'fast' })
  let m
  for (;;) { m = (await admin.req('GET', `/api/v2/orgs/${A.org.id}/conversations/${conv.id}`)).messages.at(-1); if (m.status !== 'PENDING') break; await sleep(500) }
  const answered = m.status === 'COMPLETE'
  const citedDocs = [...new Set(m.citations.map(c => Object.entries(A.docs).find(([, v]) => v.id === c.documentId)?.[0]))]
  const text = m.content.toLowerCase()
  const keywordHit = (q.keywords.length === 0 || q.keywords.some(k => text.includes(k.toLowerCase())))
    && (q.keywordsAll ?? []).every(group => group.some(k => text.includes(k.toLowerCase())))
  const leak = (q.mustNotContain ?? []).some(k => text.includes(k.toLowerCase()))
  let pass
  if (q.expect === 'ANSWER') pass = answered && citedDocs.includes(q.doc) && keywordHit
  else if (q.expect === 'REFUSE') pass = !answered && !leak
  else pass = !answered || (citedDocs.includes(q.doc) && keywordHit) // false premise: refuse, or answer with the correcting fact
  results.push({ id: q.id, category: q.category, question: q.q, expected: q.expect, status: m.status, citedDocuments: citedDocs, citedVersions: m.citations.map(c => c.versionNo),
    keywordHit, leak, pass, latencyMs: Date.now() - t0, answer: m.content })
  console.log(`${pass ? 'PASS' : 'FAIL'} ${q.id} [${q.category}] ${m.status} ${Date.now() - t0}ms ${m.content.replace(/\n/g, ' ').slice(0, 70)}`)
}
const ocr = A.docs[set.ocrCheck.document]
const byCategory = {}
for (const r of results) { byCategory[r.category] ??= { total: 0, pass: 0 }; byCategory[r.category].total++; if (r.pass) byCategory[r.category].pass++ }
const lat = results.map(r => r.latencyMs).sort((a, b) => a - b)
const report = {
  name: set.name, frozen: false, generatedAt: new Date().toISOString(),
  conditions: { model: quality ? 'quality (Qwen3 8B Q4_K_M)' : 'fast (Qwen3-4B-Instruct-2507 Q4_K_M)', embedding: 'BGE-M3 Q8_0', reranker: 'BGE-reranker-v2-m3 Q8_0',
    retrieval: 'BM25 + dense, RRF k=60, rerank gates -8.0 / 5.0', hardware: 'local CPU (developer workstation), llama.cpp b11401', questions: results.length,
    caveat: 'Keyword/document checks written by the implementer; a proxy for correctness, not independent human semantic grading.' },
  totals: { pass: results.filter(r => r.pass).length, total: results.length, ocrCheck: { expected: set.ocrCheck.expectStatus, actual: ocr?.status, pass: ocr?.status === set.ocrCheck.expectStatus },
    p50LatencyMs: lat[Math.floor(lat.length / 2)], maxLatencyMs: lat.at(-1) },
  summary: byCategory, results }
writeFileSync('eval/platform-v1-results.json', JSON.stringify(report, null, 1))
console.log(JSON.stringify(report.totals), JSON.stringify(byCategory))
