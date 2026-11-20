# Codex task T01 — Atlas platform web shell (Vue 3 + TS + Router + Pinia)

You are working in a git worktree of the Atlas repo on branch `codex/frontend-shell`.
Architect/integrator: Claude Code. You implement a well-scoped frontend task. Read `docs/platform/API.md` first: its
TypeScript shapes and endpoint table are the contract. The backend for these endpoints is being built in parallel; you
cannot run it, so code strictly against the contract.

## Allowed to modify / create
- `frontend/src/**`, `frontend/index.html`, `frontend/vite.config.ts` (only to add a dev proxy `/api` -> `http://127.0.0.1:18081`).
- Do NOT modify: `frontend/package.json` / lockfile (vue-router 4.6.5 and pinia 3.0.4 are already installed), `frontend/e2e/**`,
  anything outside `frontend/`. No git commit/push/merge, no network installs, no production servers.

## Hard requirements
1. **Keep the legacy UI working unchanged.** Move `frontend/src/App.vue` to `frontend/src/legacy/LegacyApp.vue` and `frontend/src/api.ts`
   to `frontend/src/legacy/api.ts` (fix imports only, no behaviour change; keep legacy `style.css` scoped so it applies to legacy only).
   On boot `main.ts` calls `GET /api/status`: if the JSON has `mode === 'PLATFORM'` mount the new platform app (router+pinia);
   otherwise mount `LegacyApp` exactly as before (no router). The existing Playwright test `frontend/e2e/assistant.spec.ts` must keep passing
   against the legacy backend, so the legacy DOM/text must be identical.
2. New code under `frontend/src/platform/`:
   - `api/types.ts`: copy the TypeScript shapes from API.md verbatim.
   - `api/client.ts`: fetch wrapper: `credentials:'same-origin'`, JSON, reads `XSRF-TOKEN` cookie and sends `X-XSRF-TOKEN` on non-GET, calls
     `GET /api/v2/auth/csrf` once before the first mutating request, parses `{code,message,traceId}` into an `ApiError` class, supports
     multipart upload with progress (XMLHttpRequest) for document upload. Typed functions for **every endpoint in API.md**.
   - `stores/session.ts` (Pinia): `me`, `currentOrgId` (persist last org id in localStorage inside try/catch), `currentRole`, `isSystemAdmin`,
     `load()`, `login()`, `logout()`, `switchOrg()`. `stores/status.ts` for `PlatformStatus`.
   - `router.ts`: routes `/login`, `/register`, `/invite/:token` (public); authenticated layout with `/o/:orgId/overview`, `/o/:orgId/chat/:conversationId?`,
     `/o/:orgId/knowledge-bases`, `/o/:orgId/knowledge-bases/:kbId`, `/o/:orgId/documents`, `/o/:orgId/documents/:docId`,
     `/o/:orgId/agents`, `/o/:orgId/agents/:agentId`, `/o/:orgId/runs/:runId`, `/o/:orgId/approvals`, `/o/:orgId/connectors`,
     `/o/:orgId/evaluation`, `/o/:orgId/usage`, `/o/:orgId/audit`, `/o/:orgId/settings`, `/admin`, `/orgs/new`.
     Guard: unauthenticated -> `/login?next=`; org not in memberships -> first membership or `/orgs/new`; admin routes need SYSTEM_ADMIN.
     Role-gated nav: Usage/Audit/Connectors/Settings visible to OWNER/ADMIN; Approvals to OWNER/ADMIN/EDITOR; Admin to SYSTEM_ADMIN.
   - `layouts/AppLayout.vue`: left sidebar (collapsible; becomes a top bar + drawer under 768px), org switcher, user menu (change password dialog, logout),
     main area with page header slot.
   - Shared components in `components/`: `PageHeader`, `EmptyState`, `ErrorBanner` (shows message + traceId), `StatusBadge` (document/job/run/approval states,
     colour + text, accessible), `ConfirmDialog`, `DataTable` (simple, responsive: stacks into cards on mobile), `Modal`, `Toast` store/host, `Spinner`.
   - Pages implemented fully in this task (real API calls, loading/empty/error/permission-denied states, no fake data):
     `LoginPage`, `RegisterPage` (only reachable when `status.registrationOpen`), `AcceptInvitationPage`, `CreateOrgPage`, `OverviewPage`
     (counts from knowledge-bases/documents/conversations/approvals lists), `KnowledgeBasesPage` (list + create dialog with workspace select),
     `KnowledgeBaseDetailPage` (documents in KB, upload with progress for .md .txt .pdf .docx .xlsx, KB members management when access=MANAGE,
     edit/delete KB), `DocumentsPage` (all readable docs, filter by KB and status, auto-refresh every 3 s while any doc is PROCESSING),
     `DocumentDetailPage` (versions table with pipeline state/error, upload new version, retry/cancel failed/processing version, reindex, delete,
     download original, chunk browser for a version showing locator page/lines/sheet/row), `ChatPage` (conversation list, new conversation picking KBs,
     message thread, composer with fast/quality model select when `status.models` has quality available, poll the conversation every 2 s while an
     assistant message is PENDING, citations rendered as numbered chips that open a side panel with title, version, locator, quote and a
     "view source" button that fetches the chunk endpoint and shows a clear notice on 409 VERSION_SUPERSEDED / 404),
     `SettingsPage` (org name, members table with role/status edit and remove, invitations create/list/revoke showing the one-time invite link
     `${location.origin}/invite/<token>` exactly once with a copy button, workspaces list/create and workspace members).
   - Pages for later tasks (agents, runs, approvals, connectors, evaluation, usage, audit, admin): create the route components as minimal
     placeholders that render `EmptyState` with text "此頁面將於下一批次接上 API" and NO interactive controls (no dead buttons).
3. **UI language: Traditional Chinese (zh-TW)** labels; keep technical identifiers in English. Original Atlas look: clean B2B style, CSS variables
   in `platform/styles/tokens.css` for colors/spacing/radius with light and dark (`prefers-color-scheme`) themes; no external fonts/CDNs; no UI library.
   Desktop-first, usable at 375px wide (no horizontal scroll). Keyboard focus visible. Buttons that cannot work are not rendered (or disabled with a reason).
4. Security: never render server text with `v-html`; document text, quotes and model output are untrusted. Don't store anything sensitive in localStorage.

## Done when
- `cd frontend && npm run build` (vue-tsc typecheck + vite build) passes with zero errors.
- Report: list of files created/changed, any contract ambiguity you hit (do not invent endpoints), and the build output tail.
