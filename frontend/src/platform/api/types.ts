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
export interface DocumentItem { id: string; kbId: string; title: string; status: DocumentStatus; currentVersion: number | null; latestVersion: number; ownerId: string; tags: string[]; expiresAt: string | null; updatedAt: string; filename: string | null; extension: string | null; sizeBytes: number | null; versions?: DocumentVersion[] }
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
