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
| T03 | Agents, agent detail/run form, run timeline, approvals, connectors, evaluation, usage (SVG chart), audit, admin pages | `atlas-rag-wt-frontend` / `codex/frontend-ops` | `frontend/src/platform/**` | see below | see below | see below |

## Rules applied
- Task prompt states purpose, allowed paths, forbidden paths/actions, acceptance command and report format.
- Security-critical code (tenant isolation, RBAC, sessions/CSRF, migrations, tool/SSRF policy, HITL, job queue, RAG authorisation) was written by Claude, not delegated.
- A Codex claim is never recorded as verified until Claude re-ran the check in the integration tree.
