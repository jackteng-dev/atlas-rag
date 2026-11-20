# Codex task T04 — write docs/platform/DB_SCHEMA.md from the Flyway migrations

Worktree `atlas-rag-wt-tests`, branch `codex/db-schema-doc`. Create exactly one file: `docs/platform/DB_SCHEMA.md`.
Do not modify anything else; no git commit/push/merge; no network.

Source of truth: `backend/src/platform/resources/db/migration/V1__platform_core.sql`, `V2__http_sessions.sql`, `V3__demo_erp_readonly.sql`.
Also read `backend/src/main/java/dev/knowledge/platform/**` only to describe how tables are used (do not guess beyond the code).

Content (English, concise, accurate):
1. Overview: PostgreSQL 15+ (tested on 17), Flyway versioned migrations, tenant invariant (every tenant row has `org_id`; children reference parents via
   composite `(org_id, id)` foreign keys so cross-tenant parent/child links are impossible), which data lives where (PostgreSQL = source of truth;
   object storage = original files keyed `org/<org>/doc/<doc>/v<n>/<sha256>`; Qdrant = derived vectors with ids only in payload).
2. A Mermaid ER diagram of the main tables and relationships (users, organizations, memberships, invitations, workspaces, workspace_members,
   knowledge_bases, kb_members, documents, document_versions, document_chunks, jobs, conversations, messages, message_citations, agents,
   agent_versions, workflow_runs, workflow_steps, tool_calls, approvals, agent_outputs, connectors, quotas, usage_events, audit_events).
3. One section per table: purpose, columns (name, type, null, default, check constraint values), keys/uniques/indexes, FKs, and the invariant it
   protects (e.g. `document_versions.pipeline_state` lifecycle; `documents.current_version_id` deferrable FK; `jobs` partial unique idempotency index;
   `invitations.token_hash` stores only SHA-256; `connectors.config` holds no secrets, `secret_ref` names a server env var).
4. Spring Session tables (V2) and the fictional `demo_erp` schema (V3) — state clearly it is demo data used only by read-only agent tools.
5. Retention / deletion notes as implemented: document delete = soft delete + purge job removes chunks, files and vectors but keeps version metadata;
   conversations soft-deleted; audit append-only.
6. "Known gaps" list: no PostgreSQL RLS yet (isolation is enforced in the service layer + composite FKs + tests), no automated retention schedule
   for usage/audit yet.

Done when the file exists, Mermaid syntax is valid, and every table in the migrations is documented. Report the table count you documented.
