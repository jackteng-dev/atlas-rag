# Migration plan — legacy JSON store → Atlas platform

Status: **implemented and tested locally** (`LegacyStoreImporter`, `LegacyMigrationTest`). Production execution belongs to the server-side
cutover (SERVER_CLOUD_HANDOFF.md) and must run against a **copy** of the production store, never the live file.

## Principles
1. The legacy store is the source of truth until cutover and is only ever **read** (the importer opens it read-only; the test asserts the bytes are unchanged).
2. Original text, document ids, version numbers, chunk ids, headings and line numbers are copied **verbatim** (no re-chunking), so every legacy
   citation `{documentId, version, chunkId}` resolves to the same text on the platform.
3. Vectors are derived data: they are rebuilt into a **new** Qdrant collection (`atlas_platform_v1`) by re-index jobs. The legacy collection
   (`atlas_local_v4` per `config/local-deployment.example.json`) is not read, modified or deleted.
4. The import is idempotent: re-running skips documents whose `legacy_id` already exists with the same SHA-256 and reports a conflict otherwise.
5. Legacy ACLs (department + allowed roles + owner) have no 1:1 mapping to org/workspace/KB roles. Non-public documents are placed in
   `RESTRICTED` knowledge bases per department and flagged `ACL_REVIEW_REQUIRED` so an owner assigns members before anyone can read them.

## Mapping
| Legacy | Platform |
|---|---|
| store file (one tenant) | one organisation chosen by `atlas.migrate.org-slug` |
| `Document.id` (slug) | `documents.legacy_id` (unique per org); new UUID `documents.id` |
| `Document.version` | `document_versions.version_no` (same number), `documents.latest_version_no` |
| `markdown` | original object `org/<org>/doc/<doc>/v<n>/<sha256>` (`text/markdown`), `document_versions.sha256` |
| `chunks[]` | `document_chunks` (same `chunk_id`, `heading`, `content`; locator `{lineStart,lineEnd,section}`), `parser_version = legacy-json-v1` |
| `metadata.department = public`, no roles | KB "Legacy — public" (`ORG` visibility) |
| other department / roles | KB "Legacy — <department>" (`RESTRICTED`), flagged for review |
| `metadata.expiresAt`, `tags` | `documents.expires_at`, `documents.tags` |
| `metadata.owner` | the migration owner user (`atlas.migrate.owner-email`); original owner name kept in the report |
| accounts JSON (bcrypt) | **not migrated automatically** — users are re-invited (passwords are never copied between systems) |
| previous versions' text | not present in the legacy store (it keeps only the current text) — nothing to migrate |

## Procedure (run on an isolated platform instance first)
1. Freeze writes on the legacy instance (maintenance window) and take the standard legacy backup (JSON, accounts, Qdrant) — existing runbook.
2. Copy the store file to the migration host; record its SHA-256.
3. Start the platform with an empty database; create the target organisation and owner (bootstrap admin or invitation).
4. Dry run:
   ```bash
   SPRING_PROFILES_ACTIVE=platform ATLAS_DB_URL=... java -jar atlas.jar \
     --atlas.migrate.legacy-store=/path/copy-of-store.json --atlas.migrate.org-slug=<slug> \
     --atlas.migrate.owner-email=<owner> --atlas.migrate.dry-run=true --atlas.migrate.report=work/migration-dry-run.json --atlas.migrate.exit=true
   ```
   Review `WOULD_IMPORT`, `ACL_REVIEW_REQUIRED`, failures. Exit code 0 = no failure, 2 = failures.
5. Real run: same command with `--atlas.migrate.dry-run=false --atlas.migrate.report=work/migration-report.json`.
   The importer verifies after writing that every imported document's `(chunk_id, content)` list equals the source and aborts otherwise.
6. Start the app normally; wait until all `INGEST_VERSION` re-index jobs are `SUCCEEDED` (`GET /api/v2/admin/jobs`).
7. Validation (record in the cutover log): source count = imported + skipped; per-document `markdownSha256` and `chunkIdsSha256` from the report;
   sample legacy citations resolve via `GET …/documents/{id}/versions/{n}/chunks/{chunkId}` with identical text; RESTRICTED KBs have the intended members;
   a set of known legacy questions answers with citations.
8. Keep the legacy instance and its data untouched for the rollback window (see ROLLBACK_PLAN.md).

## Schema migrations
Flyway `V1__platform_core`, `V2__http_sessions`, `V3__demo_erp_readonly` (all forward-only, applied automatically at startup). There are no down
migrations; rollback is by keeping the old system intact and/or restoring a backup. Future schema changes must be expand → migrate → contract.
