export interface Chunk { id: string; heading: string; text: string; lineStart: number; lineEnd: number }
export interface Metadata { department: string; owner: string; expiresAt: string | null; allowedRoles: string[]; tags: string[] }
export interface DocumentSummary { id: string; title: string; version: number; contentHash: string; updatedAt: string; chunkCount: number; metadata: Metadata }
export interface Document extends DocumentSummary { markdown: string; chunks: Chunk[] }
export interface Source { chunkId: string; documentId: string; title: string; version: number; heading: string; excerpt: string; lineStart: number; lineEnd: number; score: number; href: string }
export interface RetrievalConfiguration { chunkMaxChars: number; topK: number; coverageThreshold: number; bm25K1: number; bm25B: number; chunkLengthUnit: string; tokenizer: string }
export interface PipelineStage { name: string; status: string; elapsedMs: number; inputCount: number; outputCount: number }
export interface Answer { requestId: string; status: string; mode: string; retrieval: string; message: string; segments: {chunkId: string; quote: string}[]; sources: Source[]; coverage: number; missingTerms: string[]; indexRevision: number; elapsedMs: number; modelInputTokens: number | null; modelOutputTokens: number | null; configuration: RetrievalConfiguration; generated: {text: string; chunkIds: string[]}[]; pipeline: PipelineStage[]; evaluation: Record<string, string | number> }
export interface RagConfiguration { enabled: boolean; embedding?: string; reranker?: string; llm?: string; vectorStore?: string; chunking?: string; llmOptions?: { id: 'fast' | 'quality'; model: string }[] }
export interface Status { status: string; mode: string; retrieval: string; embedding: string; indexRevision: number; documentCount: number; chunkCount: number; coverageThreshold: number; chunkMaxChars: number; configuration: RetrievalConfiguration; rag: RagConfiguration; authentication: string; actor: string; roles?: string[]; departments?: string[] }
let authorization = ''
let authorizationVersion = 0
export function setCredentials(username: string, password: string) {
  authorizationVersion++
  authorization = username ? 'Basic ' + btoa(String.fromCharCode(...new TextEncoder().encode(`${username}:${password}`))) : ''
}
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const identity = authorizationVersion
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), path === '/questions' ? 150000 : 30000)
  try {
    const response = await fetch('/api' + path, { ...init, signal: controller.signal, credentials: 'omit', headers: { 'Content-Type': 'application/json', ...(authorization ? { Authorization: authorization } : {}), ...init?.headers } })
    if (identity !== authorizationVersion) throw new Error('IDENTITY_CHANGED · 登入身分已變更，請重新操作。')
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(`${body.code ?? response.status} · ${body.message ?? '服務暫時無法使用'}`) }
    if (response.status === 204) return undefined as T
    const body = await response.json()
    if (identity !== authorizationVersion) throw new Error('IDENTITY_CHANGED · 登入身分已變更，請重新操作。')
    // Existing browser clients and the preserved offline demo can coexist during a rolling demo update.
    if (path === '/questions') { body.generated ??= []; body.pipeline ??= []; body.evaluation ??= {} }
    if (path.startsWith('/documents')) {
      for (const doc of Array.isArray(body) ? body : [body]) {
        if (doc && 'contentHash' in doc) doc.metadata ??= { department: 'public', owner: 'public-demo', expiresAt: null, allowedRoles: [], tags: [] }
      }
    }
    return body as T
  } catch (error) { if (error instanceof DOMException && error.name === 'AbortError') throw new Error('REQUEST_TIMEOUT · 請確認後端連線後再試一次'); throw error }
  finally { window.clearTimeout(timeout) }
}

interface QuestionJob { id: string; status: string; answer: Answer | null; error: {code: string; message: string} | null; elapsedMs: number }
export async function askInBackground(request: {question: string; documentIds: string[]; model?: 'fast' | 'quality'}, progress: (seconds: number) => void): Promise<Answer> {
  const identity = authorizationVersion
  const job = await api<QuestionJob>('/question-jobs', { method: 'POST', body: JSON.stringify(request) })
  // The optional larger CPU model loads on first use and answers slowly; allow it more time.
  const deadline = performance.now() + (request.model === 'quality' ? 900000 : 300000)
  while (performance.now() < deadline) {
    if (identity !== authorizationVersion) throw new Error('IDENTITY_CHANGED · 登入身分已變更，請重新提問。')
    const current = await api<QuestionJob>(`/question-jobs/${job.id}`)
    if (identity !== authorizationVersion) throw new Error('IDENTITY_CHANGED · 登入身分已變更，請重新提問。')
    if (current.status === 'COMPLETE' && current.answer) return current.answer
    if (current.status === 'FAILED') throw new Error(`${current.error?.code ?? 'JOB_FAILED'} · ${current.error?.message ?? '提問未完成，請稍後重試。'}`)
    progress(Math.floor(current.elapsedMs / 1000))
    await new Promise(resolve => window.setTimeout(resolve, 2000))
  }
  throw new Error('REQUEST_TIMEOUT · 本地模型仍未完成。請稍後再試，或縮小文件範圍。')
}
