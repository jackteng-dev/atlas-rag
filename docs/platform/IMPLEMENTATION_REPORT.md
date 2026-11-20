# Implementation report — Atlas AI Platform v1 (branch `feature/atlas-platform-v1`)

Legend: ✅ implemented and tested · 🟡 implemented, partially tested or with stated limits · ⏳ not implemented (Future Module, disabled in UI)

## Function matrix (PRD §3.1)

| ID | Module | Status | What exists | Verified by |
|---|---|---|---|---|
| CORE-01 | Tenant / Organization / Workspace | ✅ | orgs, slug, memberships, invitations (single-use, hashed, 7 d), workspaces + members, org switcher | `IdentityTenantTest`, smoke |
| CORE-02 | IAM / RBAC / ACL | ✅ | sessions (Spring Session JDBC), CSRF, bcrypt, throttle, revocation, bootstrap admin, OWNER/ADMIN/EDITOR/VIEWER, workspace MANAGER, KB EDITOR/VIEWER, ORG/RESTRICTED KBs, last-owner guard, system admin | `IdentityTenantTest`, `KbAccessMatrixTest`, `LoginThrottleTest`, `PasswordsTest` |
| CORE-03 | SQL / Object / Vector storage | ✅ | PostgreSQL + Flyway V1–V3 (composite tenant FKs), S3 (MinIO client; RustFS locally) or filesystem, Qdrant with org/version payload | all integration tests, smoke |
| DOC-01 | Upload & parsing | ✅ / 🟡 | MD/TXT/PDF/DOCX/XLSX real parsers with page/line/sheet/row locators; magic-byte + zip-bomb checks; `NEEDS_OCR`. 🟡 no OCR engine, no antivirus | `DocumentParserTest` (41), `DocumentChatIsolationTest` |
| DOC-02 | Knowledge bases / versions | ✅ | KB CRUD + members, versions, atomic current-version switch, supersede, duplicate detection, retry/cancel/reindex, delete + purge (chunks, files, vectors) | `DocumentChatIsolationTest` |
| RAG-01 | Answer engine | ✅ | BM25 + BGE-M3 + RRF(60) + BGE reranker gates + Qwen3 schema-constrained generation; citations ⊆ authorised evidence; refusal | integration (fake models), smoke + eval (real models) |
| RAG-02 | Chat & history | ✅ | conversations per user, multi-KB, short follow-up context, citation states CURRENT/SUPERSEDED/UNAVAILABLE, hidden answers after access loss | `DocumentChatIsolationTest`, eval |
| RUN-01 | Jobs / worker | ✅ | PostgreSQL SKIP LOCKED queue, leases + heartbeat, retries/backoff, cancel, idempotency, final-failure hooks, restart recovery | integration tests, restart drill |
| AI-01 | Model gateway | ✅ | local-only provider, fast/quality (4B / on-demand 8B), fair concurrency gate, health, disabled mode | smoke, eval, admin health |
| AI-02 | Agent / workflow | ✅ | 3 working templates, versioned definitions, sequential steps (RETRIEVE, LLM, EXTRACT, RULE, TOOL, APPROVAL, OUTPUT), timeline, cancel/retry | `AgentWorkflowTest`, smoke |
| AI-03 | HITL / tool security | ✅ | approval queue, no self-approval (non-admin), approval-gated single write path, tool whitelist enforced at definition and run time, tool-call log | `AgentWorkflowTest`, `RuleEvaluateTest` |
| AI-04 | Connector / MCP | 🟡 | HTTP GET (server host allow-list, SSRF/private-IP block, no redirects, path allow-list, secretRef), SQL_READONLY named SELECTs, MCP `tools/call` adapter with endpoint + tool allow-lists. 🟡 MCP tested for policy only (no MCP server in tests); HTTP GET not exercised against a real external host | unit-level policy checks; manual |
| OPS-01 | Usage / quota / audit | ✅ | usage events (tokens, latency, status, trace), monthly token/request quotas, per-minute org rate limit, audit trail with denials | integration tests, smoke |
| OPS-02 | Admin / monitoring | ✅ | system health (DB, storage, Qdrant, models, jobs, OCR), orgs/users/jobs lists, user disable + session revocation, evaluation endpoint | smoke (`admin health`) |
| UX-01 | Web UI | ✅ / 🟡 | Vue Router + Pinia app: login/register/invite, org switcher, overview, chat with citation panel, KBs, documents (upload progress, versions, chunks), settings; agents/runs/approvals/connectors/evaluation/usage/audit/admin (Codex T03, see delegation log). Legacy UI preserved | `npm run build` (vue-tsc), Playwright (see HANDOFF) |
| DEL-01 | Local deployment | ✅ | compose (PostgreSQL 17, RustFS, Qdrant 1.19.1, optional app), Dockerfile, `.env.example`, smoke/load/restart/eval scripts, backup/restore | runs recorded in HANDOFF |
| DEL-02 | Server handoff | ✅ | SERVER_CLOUD_HANDOFF.md, MIGRATION_PLAN, ROLLBACK_PLAN, compatibility matrix | — |

## Future modules (not implemented; not presented as available)
OCR engine (status `NEEDS_OCR` only) · visual drag-and-drop workflow editor · branching/parallel workflow steps · multi-agent / A2A · GraphRAG ·
OIDC/SSO/MFA · billing/payments · persistent lexical index for very large corpora · PostgreSQL RLS · antivirus scanning · retention scheduler ·
per-user rate limits · i18n beyond zh-TW UI.

## Known limitations
- Lexical retrieval scans the authorised chunks per query (≤ 50 000); fine for small/medium tenants, not for large corpora.
- Small local models: cross-document multi-part questions are often answered partially (eval P11). Citation validity ≠ semantic correctness.
- One inference slot by default on CPU: throughput is bounded; queues only make waiting fair.
- Legacy accounts are not migrated; legacy ACLs need owner review after import.
- Platform-created data has no automatic sync back to legacy (rollback = traffic switch + manual reconciliation).

## Branch / commits
See `docs/HANDOFF.md` (final head SHA, Draft PR link and test command outputs).
