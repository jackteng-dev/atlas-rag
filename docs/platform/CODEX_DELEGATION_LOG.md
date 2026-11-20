# Codex delegation log

Claude Code (architect / integrator) delegated well-scoped, low-risk tasks to the **local Codex CLI** bundled with the Codex
desktop app (`codex-cli 0.167.0-alpha.3.2`, `C:\Users\<user>\AppData\Local\OpenAI\Codex\bin\…\codex.exe`, signed in with ChatGPT).
`codex` is not on PATH in this environment, so it was invoked by absolute path. Every task ran with `codex exec --sandbox workspace-write`
inside its **own git worktree and branch**, never in the main working tree, so Claude and Codex never edited the same files at the same time.
Codex never committed, pushed, merged, touched `main`, servers or credentials. Claude reviewed each diff, ran the tests, committed on the
Codex branch and merged into `feature/atlas-platform-v1`.

Task prompts are versioned in [`codex-tasks/`](codex-tasks/).

| # | Task | Worktree / branch | Files | Codex result (self-reported) | Claude review & verification | Integrated |
|---|---|---|---|---|---|---|
| T01 | Platform web shell: router, Pinia, typed API client, layout, auth pages, KB/documents/chat/settings pages; keep legacy UI | `atlas-rag-wt-frontend` / `codex/frontend-shell` | `frontend/src/**` (legacy moved to `src/legacy/`, new `src/platform/`) | `npm run build` passed; reported contract gaps (KB/workspace member shapes, admin/evaluation shapes) | Diff reviewed: no `v-html`, server text rendered as text, localStorage only for last org id (try/catch), CSRF header + `/auth/csrf` bootstrap, XHR upload with progress, legacy mount path unchanged. Rebuilt in main tree: typecheck + build PASS. Contract gaps fixed in `API.md` | ✅ merge `286f3a7` |
| T02 | Pure unit tests: DocumentParser, Passwords, LoginThrottle, KB access matrix, rule evaluation | `atlas-rag-wt-tests` / `codex/backend-unit-tests` | 5 new test files only | 216 tests, 0 failures (ran Maven offline). Reported a boundary bug in `LoginThrottle.bump()` (window reset used `isBefore` while `blocked()` used `isAfter`) | Verified only new test files; production code untouched. **Bug confirmed and fixed by Claude** (reset when `!start+15m isAfter(now)`). Full suite re-run (see HANDOFF) | ✅ merge `bdae760` |
| T03 | Agents, agent detail/run form, run timeline, approvals, connectors, evaluation, usage (SVG chart), audit, admin pages | `atlas-rag-wt-frontend` / `codex/frontend-ops` | 15 modified + 4 new files under `frontend/src/platform/**` | build PASS; self-checked with mocked API in a browser; noted API.md lacked `enabled` on agent PUT (backend supports it) | Diff reviewed (no `v-html`, JSON rendered as text). Integrated, rebuilt, then **real-backend Playwright journey written by Claude** (`e2e/platform.spec.ts`) passed: login, upload, chat citation + source, cross-tenant block, agent run, 375 px layout. Claude tightened two weak assertions it found in its own spec (step badge vs run status; blank mobile screenshot) | ✅ merge `d795cf3` |
| T04 | `docs/platform/DB_SCHEMA.md` from Flyway SQL | `atlas-rag-wt-tests` / `codex/db-schema-doc` | 1 new doc | 30 tables / 266 columns documented, Mermaid ER | Spot-checked against SQL and handlers (it correctly noted `VALIDATING` is never persisted and `job_id` has no FK) | ✅ merge `b4c31c3` |
| T05 | Owner feedback: chat-app style conversation (bubbles, pinned composer, Enter to send, model names, timing, collapsible pipeline), multi-file drag-and-drop upload queue, file type/size in lists | `atlas-rag-wt-frontend` / `codex/chat-ux` | 8 modified + 3 new files under `frontend/src/platform/**` (backend `filename/extension/sizeBytes` added by Claude first) | build PASS; mocked-API browser checks incl. IME, dark mobile, queue cancel | Diff reviewed; real-backend e2e updated by Claude and PASS: 3 files selected → `.exe` rejected client-side, 2 uploaded sequentially and READY, MD/TXT badges, start screen, Enter-to-send, citation chip + source; no horizontal overflow on KB/documents/chat/overview at 1440 px | ✅ merge (see git log) |

## Rules applied
- Task prompt states purpose, allowed paths, forbidden paths/actions, acceptance command and report format.
- Security-critical code (tenant isolation, RBAC, sessions/CSRF, migrations, tool/SSRF policy, HITL, job queue, RAG authorisation) was written by Claude, not delegated.
- A Codex claim is never recorded as verified until Claude re-ran the check in the integration tree.
