import type * as T from './types'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId = '') {
    super(message); this.name = 'ApiError'
  }
}
const base = '/api/v2'
const id = encodeURIComponent
const org = (o: string) => `${base}/orgs/${id(o)}`
const r = (o: string, name: string, key?: string) => `${org(o)}/${name}${key === undefined ? '' : '/' + id(key)}`
const q = (values: Record<string, string | number | undefined>) => {
  const p = new URLSearchParams()
  Object.entries(values).forEach(([k, v]) => { if (v !== undefined && v !== '') p.set(k, String(v)) })
  return p.size ? '?' + p : ''
}
function token() {
  const cookie = document.cookie.split(';').map(c => c.trim()).find(c => c.startsWith('XSRF-TOKEN='))
  try { return cookie ? decodeURIComponent(cookie.slice(11)) : '' } catch { return '' }
}
let csrfPromise: Promise<void> | undefined
export function csrf(): Promise<void> {
  return csrfPromise ??= fetch(base + '/auth/csrf', { credentials: 'same-origin' })
    .then(async res => { if (!res.ok) throw await responseError(res) })
    .catch(e => { csrfPromise = undefined; throw e })
}
async function responseError(res: Response): Promise<ApiError> {
  const b = await res.json().catch(() => ({}))
  return new ApiError(res.status, b?.code ?? 'HTTP_ERROR', b?.message ?? `請求失敗（${res.status}）`, b?.traceId ?? res.headers.get('X-Trace-Id') ?? '')
}
export async function request<R>(path: string, method = 'GET', body?: unknown): Promise<R> {
  if (method !== 'GET') await csrf()
  const res = await fetch(path, {
    method, credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(method === 'GET' ? {} : { 'X-XSRF-TOKEN': token() }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!res.ok) throw await responseError(res)
  if (res.status === 204) return undefined as R
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as R
}
async function upload<R>(path: string, file: File, progress?: (percent: number) => void, title?: string): Promise<R> {
  await csrf()
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', path); xhr.withCredentials = true; xhr.timeout = 120000
    xhr.setRequestHeader('X-XSRF-TOKEN', token()); xhr.setRequestHeader('Accept', 'application/json')
    xhr.upload.onprogress = e => { if (e.lengthComputable) progress?.(Math.round(e.loaded / e.total * 100)) }
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK_ERROR', '上傳連線失敗，請重試。'))
    xhr.ontimeout = () => reject(new ApiError(0, 'TIMEOUT', '上傳逾時，請重新整理確認文件狀態。'))
    xhr.onabort = () => reject(new ApiError(0, 'CANCELLED', '上傳已取消。'))
    xhr.onload = () => {
      let body: any
      try { body = xhr.responseText ? JSON.parse(xhr.responseText) : undefined }
      catch { reject(new ApiError(xhr.status, 'INVALID_RESPONSE', '伺服器回應格式無效。')); return }
      if (xhr.status >= 200 && xhr.status < 300) { progress?.(100); resolve(body as R) }
      else reject(new ApiError(xhr.status, body?.code ?? 'HTTP_ERROR', body?.message ?? '上傳失敗。', body?.traceId ?? xhr.getResponseHeader('X-Trace-Id') ?? ''))
    }
    const data = new FormData(); data.append('file', file); if (title) data.append('title', title)
    xhr.send(data)
  })
}
type KbInput = Pick<T.KnowledgeBase, 'workspaceId' | 'name' | 'description' | 'visibility'>
type AgentInput = Pick<T.Agent, 'name' | 'template' | 'description' | 'definition'>

export const api = {
  status: () => request<T.PlatformStatus>('/api/status'), csrf,
  me: () => request<T.Me>(base + '/auth/me'),
  login: (b: { email: string; password: string }) => request<T.Me>(base + '/auth/login', 'POST', b),
  logout: () => request<void>(base + '/auth/logout', 'POST'),
  register: (b: { email: string; password: string; displayName: string; orgName: string }) => request<T.Me>(base + '/auth/register', 'POST', b),
  changePassword: (b: { currentPassword: string; newPassword: string }) => request<void>(base + '/auth/password', 'POST', b),
  acceptInvitation: (b: { token: string; displayName?: string; password?: string }) => request<T.Me>(base + '/invitations/accept', 'POST', b),
  createOrg: (b: { name: string; slug: string }) => request<T.Org>(base + '/orgs', 'POST', b),
  org: (o: string) => request<T.Org>(org(o)),
  updateOrg: (o: string, b: { name: string }) => request<void>(org(o), 'PATCH', b),
  members: (o: string) => request<T.Member[]>(r(o, 'members')),
  updateMember: (o: string, u: string, b: { role?: T.OrgRole; status?: T.Member['status'] }) => request<void>(r(o, 'members', u), 'PATCH', b),
  removeMember: (o: string, u: string) => request<void>(r(o, 'members', u), 'DELETE'),
  invitations: (o: string) => request<T.Invitation[]>(r(o, 'invitations')),
  createInvitation: (o: string, b: { email: string; role: T.Invitation['role'] }) => request<T.Invitation>(r(o, 'invitations'), 'POST', b),
  revokeInvitation: (o: string, key: string) => request<void>(r(o, 'invitations', key), 'DELETE'),
  workspaces: (o: string) => request<T.Workspace[]>(r(o, 'workspaces')),
  createWorkspace: (o: string, b: { name: string; description: string }) => request<T.Workspace>(r(o, 'workspaces'), 'POST', b),
  workspaceMembers: (o: string, ws: string) => request<T.WorkspaceMember[]>(r(o, 'workspaces', ws) + '/members'),
  putWorkspaceMember: (o: string, ws: string, u: string, role: 'MANAGER' | 'EDITOR' | 'VIEWER') => request<void>(r(o, 'workspaces', ws) + '/members/' + id(u), 'PUT', { role }),
  removeWorkspaceMember: (o: string, ws: string, u: string) => request<void>(r(o, 'workspaces', ws) + '/members/' + id(u), 'DELETE'),
  knowledgeBases: (o: string) => request<T.KnowledgeBase[]>(r(o, 'knowledge-bases')),
  createKnowledgeBase: (o: string, b: KbInput) => request<T.KnowledgeBase>(r(o, 'knowledge-bases'), 'POST', b),
  knowledgeBase: (o: string, kb: string) => request<T.KnowledgeBase>(r(o, 'knowledge-bases', kb)),
  updateKnowledgeBase: (o: string, kb: string, b: Partial<Pick<KbInput, 'name' | 'description' | 'visibility'>>) => request<void>(r(o, 'knowledge-bases', kb), 'PATCH', b),
  deleteKnowledgeBase: (o: string, kb: string) => request<void>(r(o, 'knowledge-bases', kb), 'DELETE'),
  kbMembers: (o: string, kb: string) => request<T.KbMember[]>(r(o, 'knowledge-bases', kb) + '/members'),
  putKbMember: (o: string, kb: string, u: string, role: 'EDITOR' | 'VIEWER') => request<void>(r(o, 'knowledge-bases', kb) + '/members/' + id(u), 'PUT', { role }),
  removeKbMember: (o: string, kb: string, u: string) => request<void>(r(o, 'knowledge-bases', kb) + '/members/' + id(u), 'DELETE'),
  documents: (o: string, kbId?: string) => request<T.DocumentItem[]>(r(o, 'documents') + q({ kbId })),
  uploadDocument: (o: string, kb: string, file: File, progress?: (n: number) => void, title?: string) => upload<T.DocumentItem>(r(o, 'knowledge-bases', kb) + '/documents', file, progress, title),
  document: (o: string, doc: string) => request<T.DocumentItem>(r(o, 'documents', doc)),
  updateDocument: (o: string, doc: string, b: { title?: string; tags?: string[]; expiresAt?: string | null }) => request<void>(r(o, 'documents', doc), 'PATCH', b),
  deleteDocument: (o: string, doc: string) => request<void>(r(o, 'documents', doc), 'DELETE'),
  uploadVersion: (o: string, doc: string, file: File, progress?: (n: number) => void) => upload<T.DocumentItem>(r(o, 'documents', doc) + '/versions', file, progress),
  retryVersion: (o: string, doc: string, n: number) => request<void>(r(o, 'documents', doc) + '/versions/' + n + '/retry', 'POST'),
  cancelVersion: (o: string, doc: string, n: number) => request<void>(r(o, 'documents', doc) + '/versions/' + n + '/cancel', 'POST'),
  reindexDocument: (o: string, doc: string) => request<void>(r(o, 'documents', doc) + '/reindex', 'POST'),
  downloadOriginal: async (o: string, doc: string, n: number): Promise<Blob> => {
    const res = await fetch(r(o, 'documents', doc) + '/versions/' + n + '/file', { credentials: 'same-origin' })
    if (!res.ok) throw await responseError(res)
    return res.blob()
  },
  chunks: (o: string, doc: string, n: number) => request<T.Chunk[]>(r(o, 'documents', doc) + '/versions/' + n + '/chunks'),
  chunk: (o: string, doc: string, n: number, chunk: string) => request<T.Chunk>(r(o, 'documents', doc) + '/versions/' + n + '/chunks/' + id(chunk)),
  conversations: (o: string) => request<T.Conversation[]>(r(o, 'conversations')),
  createConversation: (o: string, b: { title?: string; kbIds: string[] }) => request<T.Conversation>(r(o, 'conversations'), 'POST', b),
  conversation: (o: string, key: string) => request<T.ConversationDetail>(r(o, 'conversations', key)),
  deleteConversation: (o: string, key: string) => request<void>(r(o, 'conversations', key), 'DELETE'),
  sendMessage: (o: string, key: string, b: { content: string; model?: 'fast' | 'quality' }) => request<{ userMessage: T.Message; assistantMessage: T.Message; job: T.Job }>(r(o, 'conversations', key) + '/messages', 'POST', b),
  jobs: (o: string, filters: { type?: string; state?: T.JobState } = {}) => request<T.Job[]>(r(o, 'jobs') + q(filters)),
  job: (o: string, key: string) => request<T.Job>(r(o, 'jobs', key)),
  cancelJob: (o: string, key: string) => request<void>(r(o, 'jobs', key) + '/cancel', 'POST'),
  retryJob: (o: string, key: string) => request<void>(r(o, 'jobs', key) + '/retry', 'POST'),
  agentTemplates: (o: string) => request<T.TemplateInfo[]>(r(o, 'agent-templates')),
  agents: (o: string) => request<T.Agent[]>(r(o, 'agents')),
  createAgent: (o: string, b: AgentInput) => request<T.Agent>(r(o, 'agents'), 'POST', b),
  agent: (o: string, key: string) => request<T.Agent>(r(o, 'agents', key)),
  updateAgent: (o: string, key: string, b: AgentInput & { enabled?: boolean }) => request<T.Agent>(r(o, 'agents', key), 'PUT', b),
  deleteAgent: (o: string, key: string) => request<void>(r(o, 'agents', key), 'DELETE'),
  createRun: (o: string, key: string, input: Record<string, unknown>) => request<T.Run>(r(o, 'agents', key) + '/runs', 'POST', { input }),
  runs: (o: string, agentId?: string) => request<T.Run[]>(r(o, 'runs') + q({ agentId })),
  run: (o: string, key: string) => request<T.Run>(r(o, 'runs', key)),
  cancelRun: (o: string, key: string) => request<void>(r(o, 'runs', key) + '/cancel', 'POST'),
  retryRun: (o: string, key: string) => request<void>(r(o, 'runs', key) + '/retry', 'POST'),
  approvals: (o: string, status?: T.Approval['status']) => request<T.Approval[]>(r(o, 'approvals') + q({ status })),
  approve: (o: string, key: string, comment: string) => request<void>(r(o, 'approvals', key) + '/approve', 'POST', { comment }),
  reject: (o: string, key: string, comment: string) => request<void>(r(o, 'approvals', key) + '/reject', 'POST', { comment }),
  tools: (o: string) => request<T.ToolInfo[]>(r(o, 'tools')),
  connectors: (o: string) => request<T.Connector[]>(r(o, 'connectors')),
  createConnector: (o: string, b: Pick<T.Connector, 'type' | 'name' | 'config'> & { secretRef?: string }) => request<T.Connector>(r(o, 'connectors'), 'POST', b),
  updateConnector: (o: string, key: string, b: { enabled?: boolean; config?: Record<string, unknown> }) => request<void>(r(o, 'connectors', key), 'PATCH', b),
  deleteConnector: (o: string, key: string) => request<void>(r(o, 'connectors', key), 'DELETE'),
  testConnector: (o: string, key: string) => request<{ ok: boolean; detail: string }>(r(o, 'connectors', key) + '/test', 'POST'),
  usage: (o: string, from?: string, to?: string) => request<T.UsageSummary>(r(o, 'usage') + q({ from, to })),
  quota: (o: string) => request<T.Quota>(r(o, 'quota')),
  updateQuota: (o: string, b: Pick<T.Quota, 'monthlyTokenLimit' | 'monthlyRequestLimit' | 'requestsPerMinute'>) => request<T.Quota>(r(o, 'quota'), 'PUT', b),
  audit: (o: string, filters: { limit?: number; before?: string; action?: string } = {}) => request<T.AuditEvent[]>(r(o, 'audit') + q(filters)),
  evaluation: (o: string) => request<T.EvaluationSummary>(r(o, 'evaluation')),
  adminHealth: () => request<T.Health>(base + '/admin/health'),
  adminOrgs: () => request<T.AdminOrg[]>(base + '/admin/orgs'),
  adminUsers: () => request<T.AdminUser[]>(base + '/admin/users'),
  adminJobs: () => request<T.AdminJob[]>(base + '/admin/jobs'),
  updateAdminUser: (key: string, status: T.Member['status']) => request<void>(base + '/admin/users/' + id(key), 'PATCH', { status }),
}


