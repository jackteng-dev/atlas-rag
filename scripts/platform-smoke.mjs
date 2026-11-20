// Atlas platform end-to-end smoke test over real HTTP (cookie session + CSRF), against an ISOLATED instance only.
// Usage: ATLAS_URL=http://127.0.0.1:18081 ATLAS_ADMIN_EMAIL=... ATLAS_ADMIN_PASSWORD=... node scripts/platform-smoke.mjs [--quality]
// It creates its own organisations (unique names), never deletes anything it did not create, and prints a JSON summary.
const base = process.env.ATLAS_URL ?? 'http://127.0.0.1:18081'
const adminEmail = process.env.ATLAS_ADMIN_EMAIL, adminPassword = process.env.ATLAS_ADMIN_PASSWORD
if (!adminEmail || !adminPassword) { console.error('Set ATLAS_ADMIN_EMAIL and ATLAS_ADMIN_PASSWORD'); process.exit(2) }
const quality = process.argv.includes('--quality')
const sleep = ms => new Promise(r => setTimeout(r, ms))

class Client {
  constructor(name) { this.name = name; this.cookies = new Map() }
  header() { return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
  absorb(res) { for (const c of res.headers.getSetCookie?.() ?? []) { const [pair] = c.split(';'); const i = pair.indexOf('='); this.cookies.set(pair.slice(0, i), pair.slice(i + 1)) } }
  async req(method, path, body, { expect, form } = {}) {
    if (method !== 'GET' && !this.cookies.has('XSRF-TOKEN')) await this.req('GET', '/api/v2/auth/csrf')
    const headers = { Accept: 'application/json', Cookie: this.header() }
    if (method !== 'GET') headers['X-XSRF-TOKEN'] = decodeURIComponent(this.cookies.get('XSRF-TOKEN') ?? '')
    if (body && !form) headers['Content-Type'] = 'application/json'
    const res = await fetch(base + path, { method, headers, body: form ?? (body ? JSON.stringify(body) : undefined), redirect: 'manual' })
    this.absorb(res)
    const text = await res.text(); let data; try { data = text ? JSON.parse(text) : null } catch { data = text }
    if (expect && !expect.includes(res.status)) throw new Error(`${this.name} ${method} ${path} → ${res.status} ${text.slice(0, 300)}`)
    return { status: res.status, data, trace: res.headers.get('x-trace-id') }
  }
}
const results = []
const check = (name, ok, detail = '') => { results.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`) }
async function poll(fn, done, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  for (;;) { const v = await fn(); if (done(v)) return v; if (Date.now() > deadline) throw new Error('Timed out: ' + label); await sleep(1000) }
}

const stamp = Date.now().toString(36)
const admin = new Client('admin')
const status = (await admin.req('GET', '/api/status', null, { expect: [200] })).data
check('status reports PLATFORM mode', status.mode === 'PLATFORM', JSON.stringify(status.models))
check('anonymous write rejected', (await new Client('anon').req('POST', '/api/v2/orgs', { name: 'x', slug: 'x-' + stamp })).status === 401)
await admin.req('POST', '/api/v2/auth/login', { email: adminEmail, password: adminPassword }, { expect: [200] })
const orgA = (await admin.req('POST', '/api/v2/orgs', { name: 'Smoke A ' + stamp, slug: 'smoke-a-' + stamp }, { expect: [201] })).data
const orgB = (await admin.req('POST', '/api/v2/orgs', { name: 'Smoke B ' + stamp, slug: 'smoke-b-' + stamp }, { expect: [201] })).data
const wsA = (await admin.req('GET', `/api/v2/orgs/${orgA.id}/workspaces`, null, { expect: [200] })).data[0].id
const kb = (await admin.req('POST', `/api/v2/orgs/${orgA.id}/knowledge-bases`, { workspaceId: wsA, name: 'HR policies', description: '', visibility: 'ORG' }, { expect: [201] })).data
const md = '# 請假規定\n\n特休假須於七日前提出申請，並經直屬主管核准。\n\n## 遠端工作\n\n每週最多可遠端工作兩天，須事先登記。\n'
const form = new FormData(); form.append('file', new Blob([md], { type: 'text/markdown' }), 'leave-policy.md')
const t0 = Date.now()
const doc = (await admin.req('POST', `/api/v2/orgs/${orgA.id}/knowledge-bases/${kb.id}/documents`, null, { expect: [202], form })).data
const ready = await poll(async () => (await admin.req('GET', `/api/v2/orgs/${orgA.id}/documents/${doc.id}`, null, { expect: [200] })).data,
  d => ['READY', 'FAILED', 'NEEDS_OCR'].includes(d.status), 300000, 'ingestion')
check('document ingested with real embeddings', ready.status === 'READY', `${Date.now() - t0} ms, ${ready.versions[0].chunkCount} chunks`)

// Org B (a separate user) cannot reach Org A's data.
const outsiderEmail = `smoke-b-${stamp}@example.test`, outsiderPassword = 'Smoke-outsider-' + stamp + '-Pw'
const inv = (await admin.req('POST', `/api/v2/orgs/${orgB.id}/invitations`, { email: outsiderEmail, role: 'ADMIN' }, { expect: [201] })).data
const outsider = new Client('orgB')
await outsider.req('POST', '/api/v2/invitations/accept', { token: inv.token, displayName: 'Outsider', password: outsiderPassword }, { expect: [200] })
for (const p of [`/api/v2/orgs/${orgA.id}/documents/${doc.id}`, `/api/v2/orgs/${orgA.id}/documents/${doc.id}/versions/1/file`, `/api/v2/orgs/${orgA.id}/knowledge-bases`])
  check('cross-tenant denied ' + p.split('/').slice(5).join('/'), (await outsider.req('GET', p)).status === 404)

const conv = (await admin.req('POST', `/api/v2/orgs/${orgA.id}/conversations`, { kbIds: [kb.id] }, { expect: [201] })).data
const t1 = Date.now()
await admin.req('POST', `/api/v2/orgs/${orgA.id}/conversations/${conv.id}/messages`, { content: '特休假要幾天前申請？', model: quality ? 'quality' : 'fast' }, { expect: [202] })
const answer = await poll(async () => (await admin.req('GET', `/api/v2/orgs/${orgA.id}/conversations/${conv.id}`, null, { expect: [200] })).data.messages.at(-1),
  m => m.status !== 'PENDING', 900000, 'answer')
check('grounded answer with versioned citation', answer.status === 'COMPLETE' && answer.citations.length > 0 && answer.citations[0].versionNo === 1,
  `${Date.now() - t1} ms: ${answer.content.replace(/\n/g, ' ').slice(0, 80)}`)
check('outsider cannot read the conversation', (await outsider.req('GET', `/api/v2/orgs/${orgA.id}/conversations/${conv.id}`)).status === 404)

await admin.req('POST', `/api/v2/orgs/${orgA.id}/conversations/${conv.id}/messages`, { content: '公司提供員工停車位嗎？' }, { expect: [202] })
const refusal = await poll(async () => (await admin.req('GET', `/api/v2/orgs/${orgA.id}/conversations/${conv.id}`, null, { expect: [200] })).data.messages.at(-1),
  m => m.status !== 'PENDING', 900000, 'refusal')
check('no-evidence question is refused', refusal.status === 'REFUSED', refusal.content.slice(0, 60))

const tpl = (await admin.req('GET', `/api/v2/orgs/${orgA.id}/agent-templates`, null, { expect: [200] })).data.find(t => t.template === 'READONLY_TOOL')
const agent = (await admin.req('POST', `/api/v2/orgs/${orgA.id}/agents`, { name: 'Parts lookup', template: 'READONLY_TOOL', description: '', definition: tpl.definition }, { expect: [201] })).data
const run = (await admin.req('POST', `/api/v2/orgs/${orgA.id}/agents/${agent.id}/runs`, { input: { partNo: 'MCU-100' } }, { expect: [202] })).data
const finished = await poll(async () => (await admin.req('GET', `/api/v2/orgs/${orgA.id}/runs/${run.id}`, null, { expect: [200] })).data,
  r => ['SUCCEEDED', 'FAILED'].includes(r.status), 900000, 'agent run')
check('read-only tool agent run', finished.status === 'SUCCEEDED', finished.error ?? `${finished.steps.length} steps`)

const health = await admin.req('GET', '/api/v2/admin/health')
check('admin health', health.status === 200, health.data?.components?.map(c => `${c.name}:${c.status}`).join(' '))
const failed = results.filter(r => !r.ok)
console.log(JSON.stringify({ base, quality, passed: results.length - failed.length, failed: failed.length, results }, null, 1))
process.exit(failed.length ? 1 : 0)
