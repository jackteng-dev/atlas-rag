// Restart-recovery drill (ISOLATED instance only): queue a question, then the operator restarts the server; the job must
// be picked up again after its lease expires and the answer must complete. Phase 1 prints ids, phase 2 waits.
//   node scripts/platform-restart-drill.mjs submit            → prints {orgId, conversationId}
//   node scripts/platform-restart-drill.mjs await <org> <conv> → waits for the answer and prints the job history
const base = process.env.ATLAS_URL ?? 'http://127.0.0.1:18081'
const sleep = ms => new Promise(r => setTimeout(r, ms))
const cookies = new Map()
async function req(method, path, body, form) {
  if (method !== 'GET' && !cookies.has('XSRF-TOKEN')) await req('GET', '/api/v2/auth/csrf')
  const headers = { Accept: 'application/json', Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = decodeURIComponent(cookies.get('XSRF-TOKEN') ?? '')
  if (body) headers['Content-Type'] = 'application/json'
  const res = await fetch(base + path, { method, headers, body: form ?? (body ? JSON.stringify(body) : undefined) })
  for (const c of res.headers.getSetCookie?.() ?? []) { const [p] = c.split(';'); const i = p.indexOf('='); cookies.set(p.slice(0, i), p.slice(i + 1)) }
  const text = await res.text()
  if (res.status >= 400) throw new Error(`${method} ${path} ${res.status} ${text.slice(0, 200)}`)
  return text ? JSON.parse(text) : null
}
await req('POST', '/api/v2/auth/login', { email: process.env.ATLAS_ADMIN_EMAIL, password: process.env.ATLAS_ADMIN_PASSWORD })
const [mode, orgArg, convArg] = process.argv.slice(2)
if (mode === 'submit') {
  const stamp = Date.now().toString(36)
  const org = await req('POST', '/api/v2/orgs', { name: 'Restart ' + stamp, slug: 'restart-' + stamp })
  const ws = (await req('GET', `/api/v2/orgs/${org.id}/workspaces`))[0].id
  const kb = await req('POST', `/api/v2/orgs/${org.id}/knowledge-bases`, { workspaceId: ws, name: 'Drill', description: '', visibility: 'ORG' })
  const form = new FormData(); form.append('file', new Blob(['# 備份\n\n正式資料庫每日凌晨兩點進行完整備份，保留三十天。\n']), 'backup.md')
  const doc = await req('POST', `/api/v2/orgs/${org.id}/knowledge-bases/${kb.id}/documents`, null, form)
  for (;;) { if ((await req('GET', `/api/v2/orgs/${org.id}/documents/${doc.id}`)).status === 'READY') break; await sleep(500) }
  const conv = await req('POST', `/api/v2/orgs/${org.id}/conversations`, { kbIds: [kb.id] })
  const posted = await req('POST', `/api/v2/orgs/${org.id}/conversations/${conv.id}/messages`, { content: '資料庫備份保留幾天？', model: 'quality' })
  console.log(JSON.stringify({ orgId: org.id, conversationId: conv.id, jobId: posted.job.id }))
} else if (mode === 'await') {
  const t0 = Date.now()
  for (;;) {
    const m = (await req('GET', `/api/v2/orgs/${orgArg}/conversations/${convArg}`)).messages.at(-1)
    if (m.status !== 'PENDING') {
      const job = await req('GET', `/api/v2/orgs/${orgArg}/jobs/${m.jobId}`)
      console.log(JSON.stringify({ status: m.status, content: m.content, citations: m.citations.length, waitedMs: Date.now() - t0, job }, null, 1)); break
    }
    await sleep(2000)
  }
}
