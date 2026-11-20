# Atlas Platform API v2 (contract)

Active only with the Spring profile `platform`. Legacy `/api/*` (single-instance JSON store) stays the default profile until cutover.

## Conventions

- Base path `/api/v2`. JSON bodies, UTF-8. Timestamps ISO-8601 UTC strings. IDs are UUID strings.
- **Session auth**: `POST /api/v2/auth/login` sets an `HttpOnly; SameSite=Lax` session cookie (`ATLAS_SESSION`). No bearer tokens.
- **CSRF**: every non-GET request must send header `X-XSRF-TOKEN` equal to the `XSRF-TOKEN` cookie. Call `GET /api/v2/auth/csrf` once on boot to obtain the cookie.
- **Tenant scope**: tenant resources live under `/api/v2/orgs/{orgId}/…`. The server checks the caller's membership on every request; the client-supplied orgId never grants access by itself. Unknown or foreign resources answer `404` (never revealing existence); a known resource without permission answers `403`.
- **Errors**: `{ "code": "UPPER_SNAKE", "message": "human text", "traceId": "…" }`. Every response carries header `X-Trace-Id`.
- Lists return arrays unless noted.

## TypeScript shapes (frontend source of truth)

```ts
export type OrgRole = 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER'
export interface Me { user: { id: string; email: string; displayName: string; systemRole: 'NONE' | 'SYSTEM_ADMIN' }; memberships: Membership[] }
export interface Membership { orgId: string; orgName: string; orgSlug: string; role: OrgRole }
export interface Org { id: string; name: string; slug: string; plan: string; status: string; role: OrgRole }
export interface Member { userId: string; email: string; displayName: string; role: OrgRole; status: 'ACTIVE' | 'DISABLED'; joinedAt: string }
export interface Invitation { id: string; email: string; role: Exclude<OrgRole, 'OWNER'>; expiresAt: string; acceptedAt: string | null; revokedAt: string | null; token?: string /* only in the create response */ }
export interface Workspace { id: string; name: string; description: string; createdAt: string }
export type KbAccess = 'MANAGE' | 'EDIT' | 'READ'
export interface KnowledgeBase { id: string; workspaceId: string; name: string; description: string; visibility: 'ORG' | 'RESTRICTED'; documentCount: number; readyCount: number; access: KbAccess; createdAt: string }
export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED' | 'NEEDS_OCR' | 'CANCELLED' | 'DELETING'
export type PipelineState = 'RECEIVED' | 'VALIDATING' | 'STORED' | 'PARSING' | 'CHUNKING' | 'EMBEDDING' | 'INDEXING' | 'READY' | 'FAILED' | 'NEEDS_OCR' | 'CANCELLED' | 'SUPERSEDED'
export interface DocumentVersion { versionNo: number; filename: string; mimeType: string; sizeBytes: number; sha256: string; pipelineState: PipelineState; errorCode: string | null; errorMessage: string | null; pageCount: number | null; chunkCount: number; parserVersion: string | null; createdAt: string; readyAt: string | null; jobId: string | null }
export interface DocumentItem { id: string; kbId: string; title: string; status: DocumentStatus; currentVersion: number | null; latestVersion: number; ownerId: string; tags: string[]; expiresAt: string | null; updatedAt: string; versions?: DocumentVersion[] }
export interface Locator { page?: number; lineStart?: number; lineEnd?: number; sheet?: string; row?: number; section?: string }
export interface Chunk { chunkId: string; ordinal: number; heading: string; content: string; locator: Locator; documentId: string; versionNo: number; title: string; current: boolean }
export type CitationState = 'CURRENT' | 'SUPERSEDED' | 'UNAVAILABLE'
export interface Citation { ordinal: number; documentId: string; versionNo: number; chunkId: string; title: string; quote: string; locator: Locator; state: CitationState }
export interface Message { id: string; role: 'USER' | 'ASSISTANT'; content: string; status: 'COMPLETE' | 'PENDING' | 'REFUSED' | 'FAILED'; model: string | null; createdAt: string; jobId: string | null; citations: Citation[]; meta: { pipeline?: { name: string; status: string; elapsedMs: number }[]; error?: { code: string; message: string } } }
export interface Conversation { id: string; title: string; kbIds: string[]; createdAt: string; updatedAt: string }
export interface ConversationDetail { conversation: Conversation; messages: Message[] }
export type JobState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export interface Job { id: string; type: string; state: JobState; attempts: number; maxAttempts: number; lastError: string | null; createdAt: string; updatedAt: string; traceId: string; result: unknown }
export type AgentTemplate = 'KNOWLEDGE_QA' | 'DOCUMENT_COMPARE' | 'READONLY_TOOL'
export interface StepDefinition { type: 'RETRIEVE' | 'EXTRACT' | 'RULE' | 'LLM' | 'APPROVAL' | 'TOOL' | 'OUTPUT'; name: string; config: Record<string, unknown> }
export interface AgentDefinition { model: 'fast' | 'quality'; kbIds: string[]; systemPrompt: string; tools: string[]; steps: StepDefinition[]; maxRunsPerDay: number }
export interface Agent { id: string; name: string; template: AgentTemplate; description: string; enabled: boolean; version: number; definition: AgentDefinition; updatedAt: string }
export interface TemplateInfo { template: AgentTemplate; name: string; description: string; definition: AgentDefinition; inputSchema: { field: string; label: string; type: 'text' | 'documents' | 'select'; options?: string[] }[] }
export type RunStatus = 'QUEUED' | 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'REJECTED' | 'CANCELLED'
export interface RunStep { ordinal: number; type: StepDefinition['type']; name: string; status: string; output: unknown; error: string | null; startedAt: string | null; finishedAt: string | null }
export interface Run { id: string; agentId: string; agentName: string; agentVersion: number; status: RunStatus; input: Record<string, unknown>; output: unknown; error: string | null; traceId: string; actorId: string; createdAt: string; updatedAt: string; steps: RunStep[] }
export interface Approval { id: string; runId: string; agentName: string; status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'; summary: string; payload: unknown; requestedBy: string; decidedBy: string | null; decidedAt: string | null; comment: string | null; createdAt: string }
export type ConnectorType = 'HTTP_GET' | 'SQL_READONLY' | 'MCP'
export interface Connector { id: string; type: ConnectorType; name: string; config: Record<string, unknown>; secretRef: string | null; enabled: boolean; createdAt: string }
export interface ToolInfo { id: string; name: string; description: string; sideEffect: boolean; requiresApproval: boolean; connectorId: string | null }
export interface UsageSummary { from: string; to: string; totals: { requests: number; inputTokens: number; outputTokens: number; failed: number }; byUser: { userId: string; email: string; requests: number; tokens: number }[]; byModel: { model: string; requests: number; tokens: number; avgLatencyMs: number; p95LatencyMs: number }[]; byAgent: { agentId: string; name: string; runs: number; tokens: number }[]; daily: { day: string; requests: number; tokens: number }[] }
export interface Quota { monthlyTokenLimit: number; monthlyRequestLimit: number; requestsPerMinute: number; usedTokens: number; usedRequests: number }
export interface AuditEvent { id: number; actorId: string | null; actorEmail: string | null; action: string; resourceType: string | null; resourceId: string | null; result: 'SUCCESS' | 'DENIED' | 'FAILURE'; traceId: string | null; detail: Record<string, unknown>; createdAt: string }
export interface Health { status: 'UP' | 'DEGRADED' | 'DOWN'; components: { name: string; status: 'UP' | 'DOWN' | 'DISABLED'; detail: string }[] }
export interface KbMember { userId: string; email: string; displayName: string; role: 'EDITOR' | 'VIEWER' }
export interface WorkspaceMember { userId: string; email: string; displayName: string; role: 'MANAGER' | 'EDITOR' | 'VIEWER' }
export interface EvaluationSummary { reports: { file: string; frozen: boolean; summary?: unknown; metrics?: unknown; conditions?: unknown; generatedAt?: string; configuration?: unknown; totals?: unknown; error?: string }[]; note: string }
export interface AdminOrg { id: string; name: string; slug: string; status: string; plan: string; createdAt: string; members: number; documents: number }
export interface AdminUser { id: string; email: string; displayName: string; status: 'ACTIVE' | 'DISABLED'; systemRole: 'NONE' | 'SYSTEM_ADMIN' }
export interface AdminJob { id: string; orgId: string; type: string; state: JobState; attempts: number; lastError: string; createdAt: string; updatedAt: string; traceId: string }
export interface PlatformStatus { mode: 'PLATFORM'; version: string; registrationOpen: boolean; models: { id: 'fast' | 'quality'; model: string; available: boolean }[]; ocr: 'DISABLED' | 'AVAILABLE' }
```

## Endpoints

### Public / auth
| Method | Path | Body → Result |
|---|---|---|
| GET | `/api/status` | → `PlatformStatus` (legacy profile answers its own shape without `mode:'PLATFORM'`) |
| GET | `/api/v2/auth/csrf` | → 204, sets `XSRF-TOKEN` cookie |
| POST | `/api/v2/auth/login` | `{email,password}` → `Me`; 401 `BAD_CREDENTIALS`; 429 `TOO_MANY_ATTEMPTS` |
| POST | `/api/v2/auth/logout` | → 204 |
| GET | `/api/v2/auth/me` | → `Me`; 401 `AUTH_REQUIRED` |
| POST | `/api/v2/auth/register` | `{email,password,displayName,orgName}` → `Me`; 403 `REGISTRATION_CLOSED` unless enabled |
| POST | `/api/v2/auth/password` | `{currentPassword,newPassword}` → 204; other sessions of the user are revoked |
| POST | `/api/v2/invitations/accept` | `{token, displayName?, password?}` → `Me` (new users must supply displayName+password) |

### Organization (role needed in brackets)
| Method | Path | Notes |
|---|---|---|
| POST | `/api/v2/orgs` | `{name,slug}` → `Org` (caller becomes OWNER) |
| GET | `/api/v2/orgs/{orgId}` | → `Org` [member] |
| PATCH | `/api/v2/orgs/{orgId}` | `{name}` [ADMIN] |
| GET | `/api/v2/orgs/{orgId}/members` | → `Member[]` [member] |
| PATCH | `/api/v2/orgs/{orgId}/members/{userId}` | `{role?,status?}` [ADMIN; only OWNER may grant/revoke OWNER; last OWNER protected] |
| DELETE | `/api/v2/orgs/{orgId}/members/{userId}` | [ADMIN] |
| GET/POST | `/api/v2/orgs/{orgId}/invitations` | POST `{email,role}` → `Invitation` incl. one-time `token` [ADMIN] |
| DELETE | `/api/v2/orgs/{orgId}/invitations/{id}` | revoke [ADMIN] |
| GET/POST | `/api/v2/orgs/{orgId}/workspaces` | POST `{name,description}` [ADMIN] |
| GET | `/api/v2/orgs/{orgId}/workspaces/{wsId}/members` | → `WorkspaceMember[]` [member] |
| PUT/DELETE | `/api/v2/orgs/{orgId}/workspaces/{wsId}/members/{userId}` | PUT `{role:'MANAGER'|'EDITOR'|'VIEWER'}` [ADMIN] |

### Knowledge bases and documents
| Method | Path | Notes |
|---|---|---|
| GET/POST | `/api/v2/orgs/{orgId}/knowledge-bases` | POST `{workspaceId,name,description,visibility}` [ADMIN or workspace MANAGER] |
| GET/PATCH/DELETE | `/api/v2/orgs/{orgId}/knowledge-bases/{kbId}` | PATCH `{name?,description?,visibility?}` [MANAGE] |
| GET | `/api/v2/orgs/{orgId}/knowledge-bases/{kbId}/members` | → `KbMember[]` [READ] |
| PUT/DELETE | `/api/v2/orgs/{orgId}/knowledge-bases/{kbId}/members/{userId}` | PUT `{role:'EDITOR'|'VIEWER'}` [MANAGE] |
| GET | `/api/v2/orgs/{orgId}/documents?kbId=` | → `DocumentItem[]` (only readable KBs) |
| POST | `/api/v2/orgs/{orgId}/knowledge-bases/{kbId}/documents` | multipart `file`, optional `title` → 202 `DocumentItem` [EDIT] |
| GET | `/api/v2/orgs/{orgId}/documents/{docId}` | → `DocumentItem` with `versions` |
| PATCH | `/api/v2/orgs/{orgId}/documents/{docId}` | `{title?,tags?,expiresAt?}` [EDIT] |
| DELETE | `/api/v2/orgs/{orgId}/documents/{docId}` | → 202; immediately unreadable, vectors purged by a job [EDIT] |
| POST | `/api/v2/orgs/{orgId}/documents/{docId}/versions` | multipart `file` → 202 `DocumentItem` (with versions) [EDIT]; 409 `DUPLICATE_CONTENT` |
| POST | `/api/v2/orgs/{orgId}/documents/{docId}/versions/{n}/retry` · `/cancel` | [EDIT] |
| POST | `/api/v2/orgs/{orgId}/documents/{docId}/reindex` | re-embed current version [EDIT] |
| GET | `/api/v2/orgs/{orgId}/documents/{docId}/versions/{n}/file` | original bytes, `Content-Disposition: attachment` |
| GET | `/api/v2/orgs/{orgId}/documents/{docId}/versions/{n}/chunks` | → `Chunk[]` |
| GET | `/api/v2/orgs/{orgId}/documents/{docId}/versions/{n}/chunks/{chunkId}` | → `Chunk`; 409 `VERSION_SUPERSEDED` (body still says which version is current, never the new text); 404 when deleted/unauthorized/expired |

### Chat
| Method | Path | Notes |
|---|---|---|
| GET/POST | `/api/v2/orgs/{orgId}/conversations` | POST `{title?, kbIds}` (kbIds must all be readable) |
| GET/DELETE | `/api/v2/orgs/{orgId}/conversations/{id}` | → `ConversationDetail`; only the owner |
| POST | `/api/v2/orgs/{orgId}/conversations/{id}/messages` | `{content, model?:'fast'|'quality'}` → 202 `{userMessage, assistantMessage, job}`; 429 `QUOTA_EXCEEDED`/`RATE_LIMITED` |

Citations are re-authorized on every read: a citation whose document was deleted, expired or became unreadable comes back as `state:'UNAVAILABLE'` with `quote:''`; a superseded version comes back `SUPERSEDED` (quote kept only while the old version is still readable).

### Jobs
`GET /api/v2/orgs/{orgId}/jobs?type=&state=` · `GET …/jobs/{id}` · `POST …/jobs/{id}/cancel` · `POST …/jobs/{id}/retry` (owner of the job or ADMIN).

### Agents, runs, approvals, connectors
| Method | Path | Notes |
|---|---|---|
| GET | `/api/v2/orgs/{orgId}/agent-templates` | → `TemplateInfo[]` |
| GET/POST | `/api/v2/orgs/{orgId}/agents` | POST `{name,template,description,definition}` [EDITOR] |
| GET/PUT/DELETE | `/api/v2/orgs/{orgId}/agents/{id}` | PUT `{name?,description?,definition?,enabled?}` — a new `definition` creates a new immutable version; DELETE disables |
| POST | `/api/v2/orgs/{orgId}/agents/{id}/runs` | `{input}` → 202 `Run` [member with read access to the agent's KBs] |
| GET | `/api/v2/orgs/{orgId}/runs?agentId=` · `/runs/{runId}` | → `Run[]` / `Run` |
| POST | `/api/v2/orgs/{orgId}/runs/{runId}/cancel` · `/retry` | |
| GET | `/api/v2/orgs/{orgId}/approvals?status=PENDING` | → `Approval[]` [ADMIN or EDITOR] |
| POST | `/api/v2/orgs/{orgId}/approvals/{id}/approve` · `/reject` | `{comment}`; approver must differ from the run's actor unless ADMIN/OWNER |
| GET | `/api/v2/orgs/{orgId}/tools` | → `ToolInfo[]` |
| GET/POST | `/api/v2/orgs/{orgId}/connectors` | POST `{type,name,config,secretRef?}` [ADMIN] |
| PATCH/DELETE | `/api/v2/orgs/{orgId}/connectors/{id}` | `{enabled?,config?}` [ADMIN] |
| POST | `/api/v2/orgs/{orgId}/connectors/{id}/test` | → `{ok,detail}` |

### Operations
| Method | Path | Notes |
|---|---|---|
| GET | `/api/v2/orgs/{orgId}/usage?from=&to=` | → `UsageSummary` [ADMIN] |
| GET/PUT | `/api/v2/orgs/{orgId}/quota` | → `Quota`; PUT [OWNER or SYSTEM_ADMIN] |
| GET | `/api/v2/orgs/{orgId}/audit?limit=&before=&action=` | → `AuditEvent[]` [ADMIN] |
| GET | `/api/v2/orgs/{orgId}/evaluation` | → `EvaluationSummary` (read-only) |
| GET | `/api/v2/admin/health` | → `Health` [SYSTEM_ADMIN] |
| GET | `/api/v2/admin/orgs` → `AdminOrg[]` · `/admin/users` → `AdminUser[]` · `/admin/jobs` → `AdminJob[]` | [SYSTEM_ADMIN] |
| PATCH | `/api/v2/admin/users/{id}` | `{status}` disables + revokes sessions [SYSTEM_ADMIN] |
