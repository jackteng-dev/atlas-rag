// Small concurrency probe for an ISOLATED Atlas platform instance: N members of one org ask at the same time.
// Measures queueing + CPU inference end-to-end; it does not claim production SLAs.
// Usage: ATLAS_URL=... ATLAS_ADMIN_EMAIL=... ATLAS_ADMIN_PASSWORD=... node scripts/platform-load.mjs --users 5 [--quality] [--out work/load.json]
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
const arg = (name, fallback) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : fallback }
const base = process.env.ATLAS_URL ?? 'http://127.0.0.1:18081'
const users = Number(arg('--users', '5')), out = arg('--out', null), quality = process.argv.includes('--quality')
const sleep = ms => new Promise(r => setTimeout(r, ms))
class Client {
  constructor() { this.cookies = new Map() }
  absorb(res) { for (const c of res.headers.getSetCookie?.() ?? []) { const [p] = c.split(';'); const i = p.indexOf('='); this.cookies.set(p.slice(0, i), p.slice(i + 1)) } }
  async req(method, path, body, form) {
    if (method !== 'GET' && !this.cookies.has('ATLAS_XSRF')) await this.req('GET', '/api/v2/auth/csrf')
    const headers = { Accept: 'application/json', Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
    if (method !== 'GET') headers['X-ATLAS-XSRF'] = decodeURIComponent(this.cookies.get('ATLAS_XSRF') ?? '')
    if (body) headers['Content-Type'] = 'application/json'
    const res = await fetch(base + path, { method, headers, body: form ?? (body ? JSON.stringify(body) : undefined) })
    this.absorb(res)
    const text = await res.text()
    if (res.status >= 400) throw new Error(`${method} ${path} ${res.status} ${text.slice(0, 200)}`)
    return text ? JSON.parse(text) : null
  }
}
const stamp = Date.now().toString(36)
const admin = new Client()
await admin.req('POST', '/api/v2/auth/login', { email: process.env.ATLAS_ADMIN_EMAIL, password: process.env.ATLAS_ADMIN_PASSWORD })
const org = await admin.req('POST', '/api/v2/orgs', { name: 'Load ' + stamp, slug: 'load-' + stamp })
await admin.req('PUT', `/api/v2/orgs/${org.id}/quota`, { monthlyTokenLimit: 5000000, monthlyRequestLimit: 10000, requestsPerMinute: 200 })
const ws = (await admin.req('GET', `/api/v2/orgs/${org.id}/workspaces`))[0].id
const kb = await admin.req('POST', `/api/v2/orgs/${org.id}/knowledge-bases`, { workspaceId: ws, name: 'Ops', description: '', visibility: 'ORG' })
const md = '# 出差規定\n\n國內出差需於三個工作天前提出申請。\n\n## 住宿\n\n住宿費上限為每晚新台幣三千元。\n\n## 交通\n\n高鐵可搭乘標準車廂。\n'
const form = new FormData(); form.append('file', new Blob([md]), 'travel.md')
const doc = await admin.req('POST', `/api/v2/orgs/${org.id}/knowledge-bases/${kb.id}/documents`, null, form)
for (;;) { const d = await admin.req('GET', `/api/v2/orgs/${org.id}/documents/${doc.id}`); if (d.status === 'READY') break; await sleep(500) }
const members = []
for (let i = 0; i < users; i++) {
  const email = `load-${stamp}-${i}@example.test`, password = `Load-member-${stamp}-${i}-Pw`
  const inv = await admin.req('POST', `/api/v2/orgs/${org.id}/invitations`, { email, role: 'VIEWER' })
  const c = new Client(); await c.req('POST', '/api/v2/invitations/accept', { token: inv.token, displayName: 'Load ' + i, password }); members.push(c)
}
const questions = ['國內出差要幾天前申請？', '住宿費每晚上限是多少？', '出差可以搭什麼高鐵車廂？']
const started = Date.now()
const timings = await Promise.all(members.map(async (c, i) => {
  const conv = await c.req('POST', `/api/v2/orgs/${org.id}/conversations`, { kbIds: [kb.id] })
  const t0 = Date.now()
  await c.req('POST', `/api/v2/orgs/${org.id}/conversations/${conv.id}/messages`, { content: questions[i % questions.length], model: quality ? 'quality' : 'fast' })
  for (;;) {
    const m = (await c.req('GET', `/api/v2/orgs/${org.id}/conversations/${conv.id}`)).messages.at(-1)
    if (m.status !== 'PENDING') return { user: i, ms: Date.now() - t0, status: m.status, citations: m.citations.length }
    await sleep(500)
  }
}))
const sorted = timings.map(t => t.ms).sort((a, b) => a - b)
const pct = p => sorted[Math.min(sorted.length - 1, Math.ceil(p / 100 * sorted.length) - 1)]
const summary = { base, users, quality, wallMs: Date.now() - started, p50Ms: pct(50), p95Ms: pct(95), maxMs: sorted.at(-1),
  completed: timings.filter(t => t.status === 'COMPLETE').length, refused: timings.filter(t => t.status === 'REFUSED').length,
  failed: timings.filter(t => t.status === 'FAILED').length, timings, note: 'Local CPU, 1 concurrent inference slot; answers queue fairly. Not a production SLA.' }
console.log(JSON.stringify(summary, null, 1))
if (out) { mkdirSync(dirname(out), { recursive: true }); writeFileSync(out, JSON.stringify(summary, null, 1)) }
