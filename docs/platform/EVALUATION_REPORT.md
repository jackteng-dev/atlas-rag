# Evaluation report — platform v1 (local, 2026-11-20)

All numbers below were measured on the developer workstation (Windows 11, CPU-only llama.cpp b11401, models with pinned SHA-256) against an
isolated local stack. They are **not** production SLAs and **not** independent human semantic grading.

## Frozen legacy benchmark
`eval/results.json`, `eval/retrieval-comparison.json` and the historical quality reports are untouched (no diff on this branch).

## Independent platform set `eval/platform-v1` → `eval/platform-v1-results.json`
12 questions over 3 Markdown policies + a generated 2-page PDF datasheet, a scanned (text-less) PDF and a document in a **different organisation**.
Checks: expected status (answer / refuse), cited document, expected fact keywords (all groups for the cross-document item), no foreign-tenant leak.

| Category | Pass | Notes |
|---|---|---|
| Chinese | 2/2 | |
| English, cross-lingual (EN question, mixed doc) | 2/2 | |
| Chinese-English mixed term (MCU / AQL) | 1/1 | |
| PDF (page locators) | 2/2 | |
| False premise ("why rotated every 90 days?") | 1/1 | refused rather than accepting the premise |
| No evidence | 2/2 | refusal message |
| Cross-tenant (fact only in Org B) | 1/1 | refused, no leak |
| **Cross-document (two sub-questions, two docs)** | **0/1** | answered only the NCR half; the security-incident half was omitted — known small-model limitation (max 3 statements, single-intent bias) |
| OCR check (scanned PDF) | ✅ | `NEEDS_OCR`, no fabricated text |
| **Total** | **11/12** + OCR | fast model Qwen3-4B; p50 latency 4.7 s, max 5.7 s per question (sequential) |

## Concurrency probe (`docs/platform/evidence/load-fast-6users.json`)
6 members asking simultaneously, fast model, 1 inference slot, 2 job workers: 6/6 answered with citations; p50 8.9 s, p95/max 17.7 s, wall 17.8 s.
Requests queue fairly; throughput is bounded by CPU inference (by design).

## Restart recovery (`docs/platform/evidence/restart-recovery.json`)
Quality-model question queued, server process killed while the job was RUNNING, restarted: the job was re-claimed after the 60 s lease
(attempt 2) and completed with a citation (48.7 s total including the 8B model load).

## Integration and unit tests (Testcontainers PostgreSQL, deterministic fake models)
See HANDOFF.md for the exact command and counts. Fake models validate wiring, authorisation and citation constraints only — not answer quality.

## Not measured yet
Larger corpora (> 50 k chunks per query scope), GPU, long soak tests, independent human grading, OCR quality (no OCR engine).
