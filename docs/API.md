# API contract

Base URL: `http://127.0.0.1:18081/api`. The local-web jar serves browser assets and same-origin `/api` on 18081; development Vite at 4175 proxies `/api`. JSON requests and responses. Default public-demo access; configure AKA_AUTH_USERS_FILE for stateless bcrypt Basic authentication. Public HTML/assets and status are available before login; protected `/api/**` endpoints require authentication. Error shape: `{"code":"VERSION_CONFLICT","message":"..."}`. Unknown JSON properties fail validation.

| Method | Path | Behavior |
|---|---|---|
| GET | `/status` | READY, provider mode, BM25_KEYWORD, embedding NONE, document/chunk counts, indexRevision, coverageThreshold, chunkMaxChars, configuration (effective default topK / chunk size / gate / fixed BM25 constants / tokenizer) |
| GET | `/documents` | Current document metadata, version, contentHash, updatedAt, chunkCount |
| GET | `/documents/{id}` | Full current Markdown and chunks; 404 absent |
| POST | `/documents` | Import `{id,title,markdown}`; 201 created; 409 duplicate ID |
| PUT | `/documents/{id}` | Replace `{title,markdown,expectedVersion}`; 200 current/new document; 409 stale version |
| DELETE | `/documents/{id}?expectedVersion=n` | 204 removed; 409 stale; 404 absent |
| GET | `/documents/{id}/chunks/{chunkId}?version=n` | Canonical Source; 409 stale document version, 404 absent document/chunk |
| POST | `/questions` | Synchronous compatible question endpoint; 200 answer or insufficient evidence |
| POST | `/question-jobs` | Same validated question body; 202 background job, without answer |
| GET | `/question-jobs/{id}` | 200 RUNNING / COMPLETE / FAILED; authenticated job owner only |

## Import/update examples

```json
{"id":"sample-guide","title":"Sample guide","markdown":"# Guide\n\n## Ports\nThe service runs on port 18081."}
```

```json
{"title":"Sample guide","markdown":"# Guide\n\n## Ports\nThe service runs on port 18082.","expectedVersion":1}
```

Version increments on changed title, Markdown or metadata. Chunk identity may remain stable while source version advances. No-op request does not increment. Version counters survive deletion; no archive endpoint exists.

## Question example

```json
{"question":"What backend port is used?","documentIds":["architecture"],"topK":5}
```

`documentIds` omitted or `[]` searches all documents. This is a UI selection filter, not an authorization boundary. Unknown IDs are rejected.

```json
{
  "requestId":"<server-generated UUID>",
  "status":"EXTRACTS_FOUND",
  "mode":"OFFLINE_EXTRACTIVE",
  "retrieval":"BM25_KEYWORD",
  "message":"<extraction and semantic-limits notice>",
  "segments":[{"chunkId":"<actual retrieved ID>","quote":"<exact original text>"}],
  "sources":[{
    "chunkId":"<actual retrieved ID>","documentId":"architecture",
    "title":"Knowledge Assistant 架構筆記","version":1,
    "heading":"Backend 後端","excerpt":"<chunk body>",
    "lineStart":4,"lineEnd":4,"score":1.23,
    "href":"/api/documents/architecture/chunks/<actual retrieved ID>?version=1"
  }],
  "coverage":0.6666666666666666,"missingTerms":["used"],
  "indexRevision":4,"elapsedMs":1,
  "modelInputTokens":null,"modelOutputTokens":null
}
```

This is a **shape example**, not a recorded response. Real measured source IDs/scores/request IDs are in `eval/results.json`.

INSUFFICIENT_EVIDENCE returns `segments:[]`. `sources` can include partial candidates, clearly labeled as not an answer. Zero query terms after stop word removal also yields insufficient evidence. Provider refusal can return that status even after the lexical gate passes. No fabricated fallback answer on error.

## Errors and bounds

| HTTP | Code | Meaning |
|---|---|---|
| 400 | INVALID_REQUEST | Field, JSON, slug, topK or required parameter validation failed |
| 400 | EMPTY_DOCUMENT | Headings/whitespace with no body |
| 404 | DOCUMENT_NOT_FOUND / CHUNK_NOT_FOUND | No active document/chunk |
| 409 | ID_EXISTS / VERSION_CONFLICT | Duplicate ID or stale mutation |
| 409 | STALE_CITATION / INDEX_CHANGED | Old source version or documents changed during query |
| 502 | MODEL_FAILURE / MODEL_INVALID_OUTPUT / INVALID_CITATION | Local provider unavailable, bad structured selection or invalid quote |
| 503 | STORAGE_FAILURE / MODEL_INTERRUPTED | Failed persistence or interrupted model call |
| 504 | MODEL_TIMEOUT | Local provider exceeded request timeout |
| 500 | INTERNAL_ERROR | Generic error without internal details |

Question ≤1000 characters, Markdown ≤200000, title ≤120, ID regex `[a-z0-9][a-z0-9-]{0,63}`, topK 1–5 (default 5), documentIds ≤50. Counts use Java UTF-16 code units. Individual validation runs after JSON parsing; no streaming whole-body cap is claimed. Spring may return native 404/405/415 for unmatched routes, methods or media types outside the custom error cases. Storage failure never advances the active snapshot.

Mode `LOCAL_MODEL_SELECTED_EXCERPTS` is optional and unverified with real inference. Token counts reflect provider fields if present; null in offline mode. No estimated token/cost numbers.

## Milestone 2 configuration metadata

`GET /api/status` retains the original fields and adds `configuration`. Every question response also includes `configuration`, with the **effective request topK**, including overrides. Example:

```json
{"chunkMaxChars":800,"topK":5,"coverageThreshold":0.6,"bm25K1":1.2,"bm25B":0.75,"chunkLengthUnit":"UTF16_CODE_UNITS","tokenizer":"lexical-v1"}
```

Startup variables: `AKA_CHUNK_MAX_CHARS` (32–8000, default 800), `AKA_TOP_K` (1–5, default 5), `AKA_COVERAGE_THRESHOLD` (finite 0–1, default 0.60). Invalid values fail startup; existing `topK` request validation remains 1–5. Omitted request topK uses the configured default. `coverageThreshold` and `chunkMaxChars` at status root reflect current settings. Heading text remains metadata, not evidence body.

Changing chunking requires a separate store whenever the persisted chunks differ from the configured deterministic split. Startup validates stored content hash and chunks; incompatible stores fail without rewriting Markdown, versions or source identity.

## Milestone 3 local RAG and authorization

`AKA_RAG_ENABLED=true` enables `mode=LOCAL_RAG_GENERATION`, `retrieval=HYBRID_BM25_DENSE`, `embedding=BGE_M3`; `ANSWER_GENERATED` or `INSUFFICIENT_EVIDENCE`. Status includes `rag`, `authentication` (`PUBLIC_DEMO` / `BASIC_SERVER_ACCOUNTS`), `actor`, server-derived `roles` / `departments`, and only eligible document/chunk counts. Anonymous roles/departments are empty arrays. Offline defaults remain unchanged.

Import/update optionally accept `metadata`: `{department,owner,expiresAt,allowedRoles,tags}`. Defaults are public/public-demo/null/[]/[]. Updates without metadata preserve existing policy. Nonempty expiry must parse as ISO Instant; roles follow uppercase role syntax; department/owner must be nonblank and at most 64 characters. The metadata editor uses these fields; users cannot grant themselves server roles. List exposes readable management metadata (including expired records); direct canonical source and retrieval reject expired content.

`documentIds` is a selection inside the server-authorized, unexpired scope. Unreadable/unknown/expired selected IDs or direct read/source lookup return 404. Mutations require ownership + EDITOR or ADMIN; anonymous demo may manage only its public documents. Wrong account/no login in authenticated mode returns 401 AUTH_REQUIRED, forbidden writes return 403 ACCESS_DENIED. No auth roles are accepted from client headers.

Question response adds `generated:[{text,chunkIds}]`, `pipeline:[{name,status,elapsedMs,inputCount,outputCount}]`, and `evaluation`. `segments` remain exact source excerpts, separate from generated prose. Sources carry raw reranker logits in RAG mode; these are not calibrated confidence. Configuration contains actual RAG models, collection type, candidate count, fusion, absolute threshold, maximum gap from the best rerank logit and prompt version. Tokens are actual llama.cpp usage fields; unavailable usage is null. Online faithfulness/relevance remain unmeasured; separate local Ragas batch artifacts report scores, errors and coverage.

Individual local model request timeout is 120 seconds. The RAG browser uses background jobs and polls for up to five minutes; the compatible synchronous/offline question request allows 150 seconds in the browser. Only one RAG query may run at a time (`503 MODEL_BUSY`), no answer is fabricated when a component fails. Model output bounds are three statements, each requiring canonical context citations. Final snapshot/expiry validation rejects changes during generation. Source provenance validation cannot prove generated sentence entailment.

Environment variables: `AKA_CHUNK_STRATEGY` structural/semantic, `AKA_RAG_ENABLED`, `AKA_EMBEDDING_URL`, `AKA_RERANKER_URL`, `AKA_LLM_URL`, `AKA_LLM_MODEL` (current qwen3-4b-instruct-2507), `AKA_QDRANT_URL`, `AKA_QDRANT_COLLECTION`, `AKA_CANDIDATE_K` (5–50), `AKA_RERANK_MIN_SCORE` (finite raw logit, -8), `AKA_RERANK_MAX_GAP` (0–20, default 5). Defaults and reproducible runtime are in [RAG_STACK](RAG_STACK.md) and application.properties. Models and Qdrant accept loopback origins only, without redirects or automatic remote credentials.

New local-service errors: `502 LOCAL_SERVICE_FAILURE / LOCAL_SERVICE_OUTPUT / VECTOR_INVALID_OUTPUT`, `503 VECTOR_CONFIGURATION_CHANGED`; provider details and raw error bodies are not exposed.

## Background questions for slow CPU inference

`POST /api/question-jobs` immediately returns HTTP 202 and `{"id":"<UUID>","status":"RUNNING","answer":null,"error":null,"elapsedMs":0}`. Poll `GET /api/question-jobs/{id}` with short HTTP requests. COMPLETE contains the unchanged question `answer`; FAILED contains `error:{httpStatus,code,message}` without an answer. Both routes set `Cache-Control: no-store`. The compatible `/questions` route remains synchronous and may exceed an external gateway deadline.

The worker captures the authenticated server actor at admission. Only that username can read its job; other users, unknown IDs and expired jobs receive 404 JOB_NOT_FOUND. In public demo mode, UUID possession identifies a job containing only public scope data. Publication rechecks the current document revision, source versions, current actor permissions and natural expiry; outdated evidence returns 409 INDEX_CHANGED. Login changes discard pending browser responses.

Only one background job is admitted at a time (503 MODEL_BUSY). Completed results are held in process memory for ten minutes, capped at twenty; restart loses jobs. Closing a browser or reaching its polling deadline does not cancel already admitted inference. Poll responses include elapsedMs; model token fields remain actual usage in the final answer. Do not retry by submitting concurrent jobs while the model is busy.
