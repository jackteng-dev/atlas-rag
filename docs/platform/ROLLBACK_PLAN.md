# Rollback plan

## Strategy: side-by-side, never in-place
The platform is deployed **next to** the legacy instance with its own database, bucket, Qdrant collection, port and volumes. Cutover only switches
traffic (reverse proxy / CD target). The legacy JSON store, accounts file, Qdrant collection and container image stay untouched for the whole
rollback window (recommended ≥ 14 days).

## Triggers (any one)
Cross-tenant or unauthorised data exposure · data corruption or missing documents · citations pointing to wrong text · sustained 5xx or login
failure · job queue stuck with no recovery · restore drill failure · owner decision.

## Steps
1. **Stop new writes on the platform** (maintenance page or set the proxy to legacy). Record time and reason.
2. **Switch traffic back** to the legacy instance (previous proxy target / previous image digest). The legacy CD contract was never changed on this
   branch, so the previous image runs as before.
3. **Preserve platform state** for analysis: `deploy/platform/backup.sh backup <dir>` (pg_dump + objects + manifest with SHA-256 and row counts).
   Do not delete the platform database.
4. **Reconcile data created during the platform window** (documents uploaded, answers): export from the platform DB (documents + original
   objects are complete; chat history has no legacy equivalent). Re-import documents into legacy only if the owner requires it (manual, listed in the
   incident record) — there is no automatic platform → legacy sync.
5. Verify legacy health, sample RAG answers with citations, other sites unaffected.

## Platform-internal rollback (no traffic switch needed)
- Bad document version → upload the previous file as a new version, or delete the document (purge job).
- Bad agent definition → `PUT /agents/{id}` with the previous definition (versions are immutable; runs keep their version) or disable the agent.
- Failed migration run → drop the platform database and re-run Flyway + importer (the legacy source is read-only, so this is repeatable).
- Database restore → `deploy/platform/backup.sh restore <backup>` restores into a scratch database and verifies counts; promote it by
  repointing `ATLAS_DB_URL` after review. Qdrant is rebuilt from PostgreSQL chunks with re-index jobs (`POST …/documents/{id}/reindex`).

## Tested locally
- Backup + verified restore drill (`docs/platform/evidence`, HANDOFF): counts and SHA-256 matched.
- Restart while a job was running: job resumed after lease expiry and completed (`docs/platform/evidence/restart-recovery.json`).
- Not tested here: production proxy switch, production data volumes, legacy re-import of platform-created documents.
