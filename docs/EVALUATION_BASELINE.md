# Offline evaluation — measured results

Measured at 2026-10-04T15:32:01.251Z. Corpus hashes, retrieved chunks, request IDs and raw results are in [results.json](../eval/results.json). Dataset hash: `72ca3adfba6fbac3257a717fd41bdde183383909f1959bfbd1a8649d92f6d89b`.

- Status agreement: 14/16.
- Expected source recall at 5: 13/14; all references found on 11/12 answerable questions.
- Correct refusals: 3/4.
- Exact-quote / current-version citation validity: 49/49. This does not prove semantic correctness.
- Median / p95 HTTP round trip: 2 / 59 ms (single sequential localhost run).
- BM25, topK=5, coverage threshold=0.60, chunk max=800 characters. No LLM or embedding; token use and cost are not applicable.

| ID | Category | Status agreement | Reference hits | Coverage | HTTP ms |
|---|---|---|---|---|---|
| Q01 | answerable | PASS | 1/1 | 67% | 59 |
| Q02 | answerable | PASS | 1/1 | 75% | 3 |
| Q03 | answerable | PASS | 1/1 | 100% | 2 |
| Q04 | cross-section | PASS | 2/2 | 75% | 2 |
| Q05 | cross-section | PASS | 2/2 | 67% | 2 |
| Q06 | unanswerable | PASS | 0/0 | 0% | 2 |
| Q07 | unanswerable | PASS | 0/0 | 25% | 2 |
| Q08 | unanswerable | PASS | 0/0 | 20% | 2 |
| Q09 | misleading | PASS | 1/1 | 100% | 2 |
| Q10 | misleading | PASS | 1/1 | 67% | 2 |
| Q11 | malicious | PASS | 1/1 | 67% | 2 |
| Q12 | answerable-zh | PASS | 1/1 | 100% | 3 |
| Q13 | synonym-limit | FAIL | 0/1 | 40% | 2 |
| Q14 | false-premise-limit | FAIL | 0/0 | 60% | 2 |
| Q15 | answerable | PASS | 1/1 | 100% | 2 |
| Q16 | answerable | PASS | 1/1 | 75% | 2 |

## Observed failures

- **Q13**: How are obsolete passages purged after revisions? Expected EXTRACTS_FOUND; got INSUFFICIENT_EVIDENCE, sources 0/1. Same intent as removed old text after update; lexical baseline may refuse synonym-only queries.
- **Q14**: What backend frontend port salary is approved? Expected INSUFFICIENT_EVIDENCE; got EXTRACTS_FOUND, sources 0/0. Keyword overlap with ports can cross gate despite unsupported salary premise. Baseline must not be treated as semantic validation.

## Limits

- One sequential local run; latency is descriptive, not a load benchmark.
- Reference labels authored with this project corpus; no independent holdout.
- Citation validity checks exact excerpt and provenance, not entailment of the question.
- No model inference, embeddings or token cost measured.
- Failures remain visible; no parameter comparison or semantic-quality claim.
