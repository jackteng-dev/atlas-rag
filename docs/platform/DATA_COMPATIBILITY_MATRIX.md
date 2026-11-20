# Data compatibility matrix

| Asset | Legacy (main / production today) | Platform (this branch) | Compatible? | Notes |
|---|---|---|---|---|
| Runtime selection | default profile | `SPRING_PROFILES_ACTIVE=platform` | ✅ both in one jar | Without the profile the jar behaves as legacy (47 legacy tests unchanged). |
| `backend/src/main/resources/application.properties` | used | used (base values) | ✅ unchanged | Platform settings live in `backend/src/platform/resources/application-platform.properties`. |
| `config/` (deployment, artifacts manifest) | used by `local-deploy.py` | not used | ✅ unchanged | No protected-path change on this branch. |
| `scripts/local-deploy.py`, `local-model-runtime.py` | used | not used by platform | ✅ unchanged | Model servers can be reused as-is (same ports/args). |
| JSON document store | source of truth | read-only import source | ➡️ one-way import | `LegacyStoreImporter`; verbatim ids/versions/chunks. |
| Accounts JSON (bcrypt) | Basic auth users | not imported | ❌ by design | Users are invited; passwords are not transferred. |
| Legacy roles/departments | metadata on docs | org/workspace/KB roles | ⚠️ manual review | Non-public docs → RESTRICTED KBs, flagged `ACL_REVIEW_REQUIRED`. |
| Qdrant collection (legacy) | `atlas_local_v4` (example config) | not used | ✅ untouched | Platform writes `atlas_platform_v1` (new payload schema with org/version ids). |
| Model files (GGUF) | pinned SHA-256 | same files, same llama.cpp build | ✅ | No new model; quality model is the existing optional Qwen3 8B. |
| Legacy citation URL `/api/documents/{id}/chunks/{chunk}?version=n` | legacy API | `/api/v2/orgs/{org}/documents/{uuid}/versions/{n}/chunks/{chunk}` | ⚠️ new URL, same ids | Chunk ids and version numbers preserved; a redirect shim can be added at cutover if external links exist. |
| Legacy API `/api/*` | active | inactive in platform profile | ❌ breaking | Platform UI uses `/api/v2`; `/api/status` exists in both and tells the UI which mode is running. |
| Frontend bundle | `App.vue` | legacy UI preserved under `src/legacy/`, platform under `src/platform/` | ✅ | One bundle; boot reads `/api/status` and mounts the matching app. |
| Frozen eval (`eval/results.json`, `retrieval-comparison.json`, …) | historical | read-only | ✅ untouched | New results in `eval/platform-v1-results.json`. |
| Container image recipe (`scripts/showcase-publish.py`, GHCR) | production CD | unchanged | ✅ | New `deploy/platform/Dockerfile` is for local/review builds only; production recipe decided at cutover. |
| New infrastructure | — | PostgreSQL 15+, S3-compatible object store, Qdrant | ➕ new | Must be provisioned by the server side before cutover. |
