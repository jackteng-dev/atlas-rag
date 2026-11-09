# Retrieval parameter comparison

Measured at 2026-10-04T17:31:11.475Z. Setting ID = chunk max UTF-16 units / topK / coverage threshold. BM25 k1=1.2, b=0.75, lexical-v1 tokenizer. Dataset SHA-256: `72ca3adfba6fbac3257a717fd41bdde183383909f1959bfbd1a8649d92f6d89b`.

Sequential configurations; fresh isolated store/JVM per configuration; full-dataset warmup excluded; query HTTP timing excludes citation lookups, startup and warmup; repeated fixed-order passes.

1 full warmup pass(es), 3 measured passes per setting. Logical quality counts use one pass per setting; repetitions are stability and timing measurements. Each latency distribution has 48 query samples. Historical baseline logical agreement: true; all measured repetitions stable: true.

| Chunk/topK/gate | Chunks | Status | Candidate recall | Selected recall | Correct refusals | False refusals | Citations | HTTP median / p95 ms |
|---|---|---|---|---|---|---|---|---|
| 800/5/0.6 | 16 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 49/49 | 5.038 / 8.903 |
| 800/5/0.4 | 16 | 15/16 | 13/14 | 13/14 | 3/4 | 0 | 51/51 | 5.185 / 18.258 |
| 800/5/0.8 | 16 | 8/16 | 13/14 | 4/14 | 4/4 | 8 | 20/20 | 5.603 / 18.943 |
| 800/3/0.6 | 16 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 31/31 | 4.652 / 9.197 |
| 800/3/0.4 | 16 | 15/16 | 13/14 | 13/14 | 3/4 | 0 | 33/33 | 5.104 / 8.647 |
| 800/3/0.8 | 16 | 8/16 | 13/14 | 4/14 | 4/4 | 8 | 12/12 | 4.764 / 31.937 |
| 800/1/0.6 | 16 | 13/16 | 10/14 | 9/14 | 3/4 | 2 | 11/11 | 4.664 / 8.324 |
| 800/1/0.4 | 16 | 14/16 | 10/14 | 10/14 | 3/4 | 1 | 12/12 | 4.604 / 7.820 |
| 800/1/0.8 | 16 | 7/16 | 10/14 | 3/14 | 4/4 | 9 | 3/3 | 5.187 / 10.787 |
| 400/5/0.6 | 16 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 49/49 | 4.332 / 8.803 |
| 400/5/0.4 | 16 | 15/16 | 13/14 | 13/14 | 3/4 | 0 | 51/51 | 4.514 / 9.070 |
| 400/5/0.8 | 16 | 8/16 | 13/14 | 4/14 | 4/4 | 8 | 20/20 | 4.135 / 7.937 |
| 400/3/0.6 | 16 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 31/31 | 4.263 / 7.800 |
| 400/3/0.4 | 16 | 15/16 | 13/14 | 13/14 | 3/4 | 0 | 33/33 | 4.689 / 7.903 |
| 400/3/0.8 | 16 | 8/16 | 13/14 | 4/14 | 4/4 | 8 | 12/12 | 4.538 / 7.322 |
| 400/1/0.6 | 16 | 13/16 | 10/14 | 9/14 | 3/4 | 2 | 11/11 | 4.878 / 8.659 |
| 400/1/0.4 | 16 | 14/16 | 10/14 | 10/14 | 3/4 | 1 | 12/12 | 4.354 / 8.487 |
| 400/1/0.8 | 16 | 7/16 | 10/14 | 3/14 | 4/4 | 9 | 3/3 | 4.801 / 9.045 |
| 160/5/0.6 | 30 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 51/51 | 3.981 / 7.773 |
| 160/5/0.4 | 30 | 15/16 | 13/14 | 13/14 | 3/4 | 0 | 54/54 | 4.361 / 11.045 |
| 160/5/0.8 | 30 | 7/16 | 13/14 | 3/14 | 4/4 | 9 | 15/15 | 4.874 / 8.938 |
| 160/3/0.6 | 30 | 13/16 | 12/14 | 11/14 | 3/4 | 2 | 30/30 | 4.175 / 6.354 |
| 160/3/0.4 | 30 | 15/16 | 12/14 | 12/14 | 3/4 | 0 | 36/36 | 4.268 / 7.145 |
| 160/3/0.8 | 30 | 7/16 | 12/14 | 3/14 | 4/4 | 9 | 9/9 | 5.066 / 7.436 |
| 160/1/0.6 | 30 | 12/16 | 8/14 | 7/14 | 3/4 | 3 | 10/10 | 4.444 / 6.148 |
| 160/1/0.4 | 30 | 14/16 | 8/14 | 8/14 | 3/4 | 1 | 12/12 | 4.875 / 8.547 |
| 160/1/0.8 | 30 | 6/16 | 8/14 | 2/14 | 4/4 | 10 | 2/2 | 4.923 / 9.591 |

## Per-question changes from current baseline

A status improvement with zero expected reference hits is not a retrieval improvement. Candidate recall includes sources shown after refusal; selected recall includes sections present in answer excerpts, without claiming semantic support.

### 800/5/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q13 | 1 | 0 | 0 | 0.400 → 0.400 |

### 800/5/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q04 | -1 | 0 | -2 | 0.750 → 0.750 |
| Q05 | -1 | 0 | -2 | 0.667 → 0.667 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

### 800/3/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q13 | 1 | 0 | 0 | 0.400 → 0.400 |

### 800/3/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q04 | -1 | 0 | -2 | 0.750 → 0.750 |
| Q05 | -1 | 0 | -2 | 0.667 → 0.667 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

### 800/1/0.6

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | 0 | -1 | -1 | 0.667 → 0.667 |
| Q04 | 0 | -1 | -1 | 0.750 → 0.750 |
| Q05 | -1 | -1 | -2 | 0.667 → 0.500 |

### 800/1/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | 0 | -1 | -1 | 0.667 → 0.667 |
| Q04 | 0 | -1 | -1 | 0.750 → 0.750 |
| Q05 | 0 | -1 | -1 | 0.667 → 0.500 |

### 800/1/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | -1 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q03 | -1 | 0 | -1 | 1.000 → 0.667 |
| Q04 | -1 | -1 | -2 | 0.750 → 0.750 |
| Q05 | -1 | -1 | -2 | 0.667 → 0.500 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

### 400/5/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q13 | 1 | 0 | 0 | 0.400 → 0.400 |

### 400/5/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q04 | -1 | 0 | -2 | 0.750 → 0.750 |
| Q05 | -1 | 0 | -2 | 0.667 → 0.667 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

### 400/3/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q13 | 1 | 0 | 0 | 0.400 → 0.400 |

### 400/3/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q04 | -1 | 0 | -2 | 0.750 → 0.750 |
| Q05 | -1 | 0 | -2 | 0.667 → 0.667 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

### 400/1/0.6

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | 0 | -1 | -1 | 0.667 → 0.667 |
| Q04 | 0 | -1 | -1 | 0.750 → 0.750 |
| Q05 | -1 | -1 | -2 | 0.667 → 0.500 |

### 400/1/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | 0 | -1 | -1 | 0.667 → 0.667 |
| Q04 | 0 | -1 | -1 | 0.750 → 0.750 |
| Q05 | 0 | -1 | -1 | 0.667 → 0.500 |

### 400/1/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | -1 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q03 | -1 | 0 | -1 | 1.000 → 0.667 |
| Q04 | -1 | -1 | -2 | 0.750 → 0.750 |
| Q05 | -1 | -1 | -2 | 0.667 → 0.500 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

### 160/5/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q13 | 1 | 0 | 0 | 0.400 → 0.400 |

### 160/5/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q04 | -1 | 0 | -2 | 0.750 → 0.750 |
| Q05 | -1 | 0 | -2 | 0.667 → 0.667 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q15 | -1 | 0 | -1 | 1.000 → 0.750 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

### 160/3/0.6

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q05 | 0 | -1 | -1 | 0.667 → 0.667 |
| Q15 | -1 | 0 | -1 | 1.000 → 0.500 |

### 160/3/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q05 | 0 | -1 | -1 | 0.667 → 0.667 |
| Q13 | 1 | 0 | 0 | 0.400 → 0.400 |

### 160/3/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q04 | -1 | 0 | -2 | 0.750 → 0.750 |
| Q05 | -1 | -1 | -2 | 0.667 → 0.667 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q15 | -1 | 0 | -1 | 1.000 → 0.500 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

### 160/1/0.6

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | 0 | -1 | -1 | 0.667 → 0.667 |
| Q03 | 0 | -1 | -1 | 1.000 → 0.667 |
| Q04 | 0 | -1 | -1 | 0.750 → 0.750 |
| Q05 | -1 | -2 | -2 | 0.667 → 0.500 |
| Q15 | -1 | 0 | -1 | 1.000 → 0.500 |

### 160/1/0.4

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | 0 | -1 | -1 | 0.667 → 0.667 |
| Q03 | 0 | -1 | -1 | 1.000 → 0.667 |
| Q04 | 0 | -1 | -1 | 0.750 → 0.750 |
| Q05 | 0 | -2 | -2 | 0.667 → 0.500 |

### 160/1/0.8

| Question | Status agreement delta | Candidate reference delta | Selected reference delta | Coverage before → after |
|---|---|---|---|---|
| Q01 | -1 | -1 | -1 | 0.667 → 0.667 |
| Q02 | -1 | 0 | -1 | 0.750 → 0.750 |
| Q03 | -1 | -1 | -1 | 1.000 → 0.667 |
| Q04 | -1 | -1 | -2 | 0.750 → 0.750 |
| Q05 | -1 | -2 | -2 | 0.667 → 0.500 |
| Q10 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q11 | -1 | 0 | -1 | 0.667 → 0.667 |
| Q14 | 1 | 0 | 0 | 0.600 → 0.600 |
| Q15 | -1 | 0 | -1 | 1.000 → 0.500 |
| Q16 | -1 | 0 | -1 | 0.750 → 0.750 |

## Limits

- Small corpus-authored set; no independent holdout or semantic answer accuracy.
- Source recall labels document/heading, not exact passage relevance; candidate recall includes refused answers.
- Selected-reference recall counts excerpt sections, not entailment. Citation validity proves exact text/current provenance only.
- Latency is sequential loopback measurement, not a load benchmark; fixed order and JIT/GC/host noise remain.
- No model inference, embeddings, token use or costs. Corpus statements describe the historical baseline even under experimental settings.
