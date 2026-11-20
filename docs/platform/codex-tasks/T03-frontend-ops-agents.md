# Codex task T03 — Agents, runs, approvals, connectors, evaluation, usage, audit, admin pages

Same worktree rules as T01 (you are in `atlas-rag-wt-frontend` on branch `codex/frontend-ops`). Read `docs/platform/API.md`
(updated: KbMember, WorkspaceMember, EvaluationSummary, AdminOrg/AdminUser/AdminJob shapes were added) and the existing code under
`frontend/src/platform/` (your T01 output, now reviewed and merged). Reuse its components, client, stores and styles.

## Allowed to modify / create
- `frontend/src/platform/**` only. Do NOT modify `frontend/src/legacy/**`, `frontend/package.json`, lockfile, `frontend/e2e/**`, anything outside `frontend/`.
- No git commit/push/merge, no network installs.

## Work
1. `api/types.ts`: add the new shapes from API.md; `api/client.ts`: typed functions for KB members GET, workspace members GET, evaluation, admin lists,
   using the exact shapes (remove the `unknown` placeholders T01 had to use).
2. Replace the placeholder pages with full implementations (real API calls; loading / empty / error with traceId / 403 states; zh-TW labels):
   - `AgentsPage`: list agents (name, template badge, enabled, version, updatedAt); "建立 Agent" dialog: pick a template from `GET /agent-templates`,
     name/description, multi-select knowledge bases (only for KNOWLEDGE_QA, write into `definition.kbIds`), model fast/quality (quality only if available
     in status), shows the template steps read-only; POST creates. Only EDITOR+ sees create (VIEWER sees list + run).
   - `AgentDetailPage`: definition view (steps as an ordered list with type icons/text, tools whitelist, KBs by name), enable/disable (EDITOR+),
     "執行" form generated from the template's `inputSchema` (`text` → input, `select` → select with options, `documents` → multi-select of READY documents
     from `GET /documents`), POST run then navigate to run detail; recent runs table from `GET /runs?agentId=`.
   - `RunDetailPage`: status header (StatusBadge), input JSON, traceId, timeline of steps (ordinal, type, name, status, started/finished, error), each step's
     output rendered as formatted JSON in a collapsible `<pre>` (text only, never HTML); for RULE output render a pass/fail table; for LLM output render answer
     lines and citations (title, version, locator, quote). Poll every 2 s while status is QUEUED/RUNNING. Cancel (QUEUED/RUNNING/WAITING_APPROVAL) and Retry (FAILED)
     buttons for the actor or admins. If WAITING_APPROVAL, link to Approvals.
   - `ApprovalsPage`: tabs PENDING / 全部; table with agent, summary, requested by, created; detail drawer showing payload JSON; approve / reject with optional
     comment (max 500) and ConfirmDialog; show the server's 403 message when the requester tries to approve their own run.
   - `ConnectorsPage` (ADMIN+): list; create form per type: HTTP_GET (baseUrl, allowedPaths list), SQL_READONLY (named queries map editor: name + SELECT),
     MCP (endpoint, allowedTools list, readOnly checkbox); secretRef optional text with hint "伺服器環境變數名稱，需以 ATLAS_CONNECTOR_ 開頭；密碼不會存入資料庫";
     enable/disable toggle, delete with confirm, "測試" button showing `{ok, detail}`. Also a read-only "可用工具" table from `GET /tools` (sideEffect /
     requiresApproval badges).
   - `EvaluationPage`: render each report (file, frozen badge "凍結基準，不可覆寫", generatedAt, and summary/metrics/conditions as key-value tables when objects,
     otherwise JSON `<pre>`) plus the note; explicitly say citation validity ≠ semantic accuracy.
   - `UsagePage` (ADMIN+): date range (default last 30 days), totals cards (requests, input/output tokens, failed), byModel table (avg/p95 latency ms),
     byUser, byAgent, daily table + a simple inline SVG bar chart of daily tokens (no chart library; accessible title/labels); quota card from `GET /quota`
     with used/limit progress bars and an edit form only for OWNER (PUT /quota). No currency anywhere (units only).
   - `AuditPage` (ADMIN+): table (time, actor email, action, resource, result badge, traceId) with action prefix filter and "載入更多" using `before`.
   - `AdminPage` (SYSTEM_ADMIN): health components grid (status color + detail), organisations table, users table with disable/enable (PATCH, confirm),
     jobs table (type, state, attempts, lastError, traceId). Refresh button.
   - Settings: use the new KbMember/WorkspaceMember shapes where T01 showed raw JSON.
3. Navigation: make sure the sidebar items for these pages exist with the role gating described in T01, and the Overview page links to Approvals (pending count) and Agents.
4. Mobile: tables collapse to cards under 768px (DataTable), drawers become full-screen sheets.

## Done when
`cd frontend && npm run build` passes with zero type errors. Report files changed, contract questions (do not invent endpoints), and the build tail.
