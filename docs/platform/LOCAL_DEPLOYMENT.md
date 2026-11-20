# Local deployment (development)

Everything below is local and isolated (own compose project `atlas-platform-dev`, loopback ports, own volumes). It never touches a server.

## Prerequisites
JDK 21+, Maven 3.9, Node 24, Docker with Compose v2. For real RAG answers: the pinned local models and llama.cpp (see `docs/RAG_STACK.md`);
on Linux `node scripts/setup-local-rag.mjs --download` + `scripts/local-model-runtime.py` start them on ports 18111–18114.

## 1. Infrastructure (PostgreSQL 17, S3-compatible store, Qdrant 1.19.1)
```bash
cp deploy/platform/.env.example deploy/platform/.env   # then replace every value (file is git-ignored)
docker compose -f deploy/platform/compose.yaml --env-file deploy/platform/.env up -d
```
Ports: PostgreSQL `127.0.0.1:15432`, S3 API `127.0.0.1:19000`, Qdrant `127.0.0.1:16433`.
MinIO no longer publishes public container images; the local stack uses **RustFS** (S3-compatible). Any S3 API (MinIO, Ceph RGW, AWS S3) works.

## 2. Build
```bash
npm --prefix frontend ci && npm --prefix frontend run build
mvn -f backend/pom.xml -B -ntp -Plocal-web verify      # needs Docker: platform tests use Testcontainers PostgreSQL
```

## 3. Run the platform natively (full RAG with host models)
Export the values from `deploy/platform/.env`, then:
```bash
SPRING_PROFILES_ACTIVE=platform \
ATLAS_DB_URL=jdbc:postgresql://127.0.0.1:15432/atlas ATLAS_DB_USER=atlas ATLAS_DB_PASSWORD=$ATLAS_DB_PASSWORD \
ATLAS_S3_ENDPOINT=http://127.0.0.1:19000 ATLAS_S3_ACCESS_KEY=$ATLAS_S3_ACCESS_KEY ATLAS_S3_SECRET_KEY=$ATLAS_S3_SECRET_KEY \
AKA_QDRANT_URL=http://127.0.0.1:16433 ATLAS_MODELS_ENABLED=true AKA_QUALITY_LLM_URL=http://127.0.0.1:18114 \
ATLAS_BOOTSTRAP_ADMIN_EMAIL=$ATLAS_BOOTSTRAP_ADMIN_EMAIL ATLAS_BOOTSTRAP_ADMIN_PASSWORD=$ATLAS_BOOTSTRAP_ADMIN_PASSWORD \
java -jar backend/target/knowledge-assistant-0.1.0.jar
```
Open `http://127.0.0.1:18081`. The bootstrap variables only act when the users table is empty; remove them afterwards.
Without models (`ATLAS_MODELS_ENABLED=false`) everything except embedding/answers works and model calls fail with `MODELS_DISABLED`.

Container alternative (no host models): `docker compose … --profile app up -d --build` → `http://127.0.0.1:18091`.

## 4. Verify
```bash
ATLAS_URL=http://127.0.0.1:18081 ATLAS_ADMIN_EMAIL=… ATLAS_ADMIN_PASSWORD=… node scripts/platform-smoke.mjs        # 11 end-to-end checks
ATLAS_URL=… ATLAS_ADMIN_EMAIL=… ATLAS_ADMIN_PASSWORD=… node scripts/platform-load.mjs --users 6                     # concurrency probe
ATLAS_URL=… ATLAS_ADMIN_EMAIL=… ATLAS_ADMIN_PASSWORD=… node scripts/evaluate-platform.mjs                          # eval/platform-v1
deploy/platform/backup.sh backup work/backups && deploy/platform/backup.sh restore work/backups/atlas-<ts>          # backup + verified restore
```
(On Git Bash for Windows set `MSYS_NO_PATHCONV=1` before `backup.sh`.)

## 5. Legacy import (optional)
See MIGRATION_PLAN.md (`--atlas.migrate.legacy-store=… --atlas.migrate.dry-run=true`).

## Configuration reference (platform)
| Variable | Default | Meaning |
|---|---|---|
| `ATLAS_BIND_ADDRESS` / `ATLAS_PORT` | 127.0.0.1 / 18081 | listen address/port |
| `ATLAS_DB_URL`, `ATLAS_DB_USER`, `ATLAS_DB_PASSWORD` | local compose | PostgreSQL |
| `ATLAS_STORAGE` | s3 | `s3` or `filesystem` (`ATLAS_FILE_ROOT`) |
| `ATLAS_S3_ENDPOINT`, `ATLAS_S3_BUCKET`, `ATLAS_S3_ACCESS_KEY`, `ATLAS_S3_SECRET_KEY` | — | object store |
| `AKA_QDRANT_URL`, `ATLAS_QDRANT_COLLECTION`, `ATLAS_INTERNAL_HOSTS` | 127.0.0.1:16333, atlas_platform_v1, — | vectors; internal hosts allow a non-loopback service name (containers) |
| `ATLAS_MODELS_ENABLED`, `AKA_EMBEDDING_URL`, `AKA_RERANKER_URL`, `AKA_LLM_URL`, `AKA_LLM_MODEL`, `AKA_QUALITY_LLM_URL`, `AKA_QUALITY_LLM_MODEL` | true, 18112, 18113, 18111, qwen3-4b-instruct-2507, —, qwen3-8b | loopback llama.cpp servers |
| `ATLAS_MAX_INFERENCE` | 1 | concurrent model calls |
| `ATLAS_JOB_WORKERS`, `ATLAS_JOB_LEASE_SECONDS` | 2, 60 | job workers and lease |
| `ATLAS_REGISTRATION_OPEN` | false | self-service sign-up |
| `ATLAS_COOKIE_SECURE`, `ATLAS_SESSION_TIMEOUT` | false, 12h | set `true` behind HTTPS |
| `ATLAS_MAX_UPLOAD` | 50MB | upload limit |
| `ATLAS_HTTP_TOOL_HOSTS`, `ATLAS_HTTP_TOOL_ALLOW_PRIVATE`, `ATLAS_MCP_ENDPOINTS` | —, false, — | server allow-lists for agent connectors |
| `ATLAS_CONNECTOR_*` | — | connector credentials referenced by `secretRef` |
