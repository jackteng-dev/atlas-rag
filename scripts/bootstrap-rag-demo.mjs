// Update only preserved seed content in a fresh, separate hybrid demo store; never overwrite user Markdown.
import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
const base = process.argv[2] ?? 'http://127.0.0.1:18081'
const origin = new URL(base)
if (!['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname)) throw new Error('Bootstrap requires a loopback API')
async function call(path, init) {
  const response = await fetch(base + '/api' + path, { ...init, signal: AbortSignal.timeout(120000) })
  if (!response.ok) throw new Error(`Bootstrap failed: HTTP ${response.status}`)
  return response.json()
}
const status = await call('/status')
if (status.mode !== 'LOCAL_RAG_GENERATION') throw new Error('Use a separate local RAG demo store')
for (const id of ['architecture', 'operations']) {
  const markdown = await readFile(new URL(`../backend/src/main/resources/rag-corpus-v4/${id}.md`, import.meta.url), 'utf8')
  const original = await readFile(new URL(`../backend/src/main/resources/corpus/${id}.md`, import.meta.url))
  // Historical acceptance fixtures stay frozen; only explicitly recognized public seeds can be upgraded.
  const legacyHash = { architecture: '68fae4f97495ecbc2d4a6ed80e0c213f78d4d1bf140e3904ac67e6c26f2eedfd',
    operations: '6b735622a48eb1d12fae3d780e9f7e673c87554855378a75dcb89e3efd1d7b37' }[id]
  const current = await call(`/documents/${id}`)
  if (current.markdown === markdown) continue
  if (![createHash('sha256').update(original).digest('hex'), legacyHash].includes(current.contentHash)) throw new Error(`Refusing to overwrite non-seed ${id}`)
  const saved = await call(`/documents/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: markdown.split('\n')[0].replace(/^# /, ''), markdown, expectedVersion: current.version,
      metadata: { department: 'public', owner: 'public-demo', allowedRoles: [], tags: ['rag', 'current-architecture'] } }) })
  console.log(`${id} updated to demo version ${saved.version}`)
}
