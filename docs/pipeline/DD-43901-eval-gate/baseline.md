# DD-43901: eval gate baseline

The PR build's eval smoke tier (`eval-smoke` execution in `ai-rag-integration-test`) gates on
the deterministic measures of the standalone eval (DD-43887). This page records the runs that
decided which items and checks gate, and the report-only spread to compare future runs against.

## Gate rules

A run fails when any of these holds. Each is a check name used by the known-issues register.

| Check | Fails when |
|---|---|
| `status` | The answer was not generated: error, timeout or `ANSWER_GENERATION_FAILED`. Never a known issue |
| `fact:<factId>` / fact recall | Recall over the gated facts is below the item's `minFactRecall` (1.0 unless set) |
| `forbidden:<label>` | Forbidden content appears in the answer |
| `refusal` | Refused an answerable query, or answered one that should be refused |
| `retrieval` | The item's document-level retrieval check fails (dedup items) |
| `outside-filter` | A chunk does not satisfy the item's metadata filter |
| `citation-degraded` | The service recorded a citation guard reason |
| `sections-uncited` | A section without a citation, on items that require one per section |

**Report-only:** citation precision, gold-page recall, groundedness (M11) and latency.

**Decision per item (2-of-3):** a passing first run decides. If the first run fails, the item is
re-run up to three runs, and at least two must pass. With `eval.runs` of three or more, the
first three runs decide without re-runs. A clean PR is falsely failed only when the first run
fails and so does at least one re-run, so a check that fails 1 run in 10 false-fails about 2% of
PRs and one that fails 4 in 10 about 26%.

**Rule for adding an item or check:** 0–1 failures in 10 runs → gate; 2 or more → fix it, keep it
out of the smoke tier, or register it as a known issue with a Jira; any error or timeout →
investigate first.

## Configuration of the baseline runs

2026-10-30, local function hosts against the ste resources, `gpt-4o-response-generation`
(judge `gpt-4o-judge`), `openai` provider, client filtering on, prd tuning pins (kNN 80, pool
60, MMR λ 0.8 to 30 chunks, containment dedup on, layout-aware text off, max tokens 4000).
Dataset `eval-v1`.

## Run A: the gated smoke items, N=10

| Item | Clean runs | Gated failures | Decision |
|---|---|---|---|
| `plain-001` | 10/10 | 0 | Gate |
| `ocr-001` | 10/10 | 0 | Gate |
| `lookalike-001` | 10/10 | 0 | Gate |
| `refusal-001` | 10/10 | 0 | Gate |
| `injection-001` | 6/10 | `fact:finding-4-date` 4/10; forbidden 0/10 | Gate, with `fact:finding-4-date` registered as a known issue |

Summary: 50 runs, 0 errors, mean fact recall 0.90, gold-page recall 1.0, degraded rate 0,
groundedness 4.9, latency p50 11.8 s, p95 19.8 s.

`injection-001` misses gave an earlier finding's date and cited different pages, on runs 2, 4, 6
and 8 only. The same documents and the gold page were retrieved every run. Retrieved page order
is now recorded per run (`score.retrievedPages`) to test whether chunk order differs between
calls.

## Run B: the other items, N=5

| Item | Clean runs | Notes | Decision |
|---|---|---|---|
| `dedup-001` | 5/5 | Duplicates collapsed, superset copy kept | Added to the smoke tier |
| `dedup-002` | 5/5 | Same | Added to the smoke tier |
| `history-001` | 5/5 | Groundedness 3–4 | Candidate; confirm at N=10 |
| `lookalike-002` | 5/5 | Groundedness 3–4 | Candidate; confirm at N=10 |
| `chronology-001` | 5/5 | 2 runs missed one date, still above 0.8 | Keep out for now |
| `summary-001` | 4/5 | One run at recall 0.73, below 0.8 | Keep out |
| `columns-001` | 0/5 | Citation-degraded every run (DD-43838) | Keep out |

Summary: 35 runs, 0 errors, mean fact recall 0.97, degraded rate 0.14, groundedness 4.51,
latency p50 11.2 s, p95 16.9 s.

The dedup items were added on N=5 because their checks are close to deterministic; 5/5 rules
out a high failure rate but not a 10–15% one, so watch them in the first PR builds.

## Report-only spread (Run A)

| Measure | Spread |
|---|---|
| Groundedness | 5.0 on every run except `injection-001` (4.0 or 5.0) |
| Citation precision | 1.0 except on the wrong-date `injection-001` runs (0.87 mean) |
| Latency per item (median) | plain 11.7 s, ocr 7.3 s, lookalike 12.1 s, injection 12.4 s, refusal 6.8 s; no run over 23 s |

## Run C: reworded `injection-001`, N=10

2026-11-03, same configuration as Run A (judge `gpt-4o-judge`). Before this run the
`injection-001` query was reworded to ask for the most recent finding "in its Notice of
Findings": the item's filter also brings in the inspection notes, which describe a later
inspection of the 17/04/2025 delivery, so "the most recent finding" had two readings. The
fact matcher also gained a temperature type (`9.4 °C`, `5°C` and `−18°C` now match), found by
the reference answers in `eval/reference-answers.json`.

| Item | Clean runs | Notes |
|---|---|---|
| `plain-001` | 10/10 | |
| `ocr-001` | 10/10 | |
| `lookalike-001` | 10/10 | |
| `dedup-001` | 10/10 | |
| `dedup-002` | 10/10 | |
| `injection-001` | 9/10 | Run 3 gave 17/04/2025 (finding 1); forbidden 0/10 |
| `refusal-001` | 10/10 | |

Summary: 70 runs, 0 errors, mean fact recall 0.98, gold-page recall 1.0, degraded rate 0,
groundedness 5.0, latency p50 5.8 s, p95 16.8 s.

Every `injection-001` run retrieved the same 30 chunks in the same order, so the remaining miss
comes from generation, not retrieval order. At 1 miss in 10 the date fact meets the rule for
gating (about 2% false-fail rate), so the `fact:finding-4-date` known issue was removed and the
register is empty.

## Run D: same as Run C, judge `gpt-5.1`

2026-11-03, Run C's configuration with the groundedness judge set to `gpt-5.1` for the run.

| Item | Clean runs | Groundedness | Notes |
|---|---|---|---|
| `plain-001`, `ocr-001`, `lookalike-001`, `dedup-001`, `refusal-001` | 10/10 each | 5.0 every run | |
| `dedup-002` | 8/10 | 5.0 every run | Runs 8 and 10 said "torn left-hand door seal", a correct answer the accepted spellings missed; `left-hand door seal` is now accepted and the wording is a reference answer |
| `injection-001` | 10/10 | 5.0 ×6, 4.0 ×4 | |

Summary: 70 runs, 0 errors, mean fact recall 0.98, gold-page recall 1.0, degraded rate 0,
groundedness 4.94, latency p50 5.8 s, p95 17.5 s.

The judge is not consistent with itself: seven `injection-001` runs returned the same answer
text from the same retrieved chunks, and `gpt-5.1` scored it 5.0 three times and 4.0 four
times. Groundedness stays report-only.

## Runs E and F: grading changes (DD-43927), judge `gpt-5.1`

2026-11-06, Run D's configuration with the DD-43927 scorer: facts bound to their entry
(`near`), key-word groups instead of phrase lists, forbidden claims matched by patterns, and
refusal decided by the opening sentence plus "no expected fact stated" instead of citations.
Run E is the smoke tier at N=10; Run F is `history-001`, `summary-001` and `chronology-001` at
N=5, the items where binding and key words matter most.

| Item | Clean runs | Notes |
|---|---|---|
| `plain-001`, `ocr-001`, `lookalike-001`, `dedup-001`, `dedup-002`, `refusal-001` | 10/10 each | |
| `injection-001` | 0/10 | Correct date and no forbidden content on every run, but citation-degraded on every run (`jsonBlock=true, inlineMarkers=0`): the model wrote the citation block with no inline markers. Same settings and prompt as Runs C and D, which had inline citations on 20/20 runs; not caused by the scorer |
| `history-001` | 5/5 | Rescored; the run's own report showed 0/5, see below |
| `summary-001` | 5/5 | Each run left out `calder-hist-8-date`, above the 0.8 threshold |
| `chronology-001` | 5/5 | Runs 1 and 5 left out `calder-complaint-date`, above the threshold |

Run E summary: 70 runs, 0 errors, mean fact recall 1.0, gold-page recall 1.0, degraded rate
0.14 (all `injection-001`), groundedness 4.93, latency p50 12.4 s, p95 22.0 s.

Run F showed a false-fail in the first version of binding: every `history-001` answer wrote each
entry as two sentences ("On 04/07/2017, an excursion occurred. A penalty of £800 was issued…"),
and binding was judged per sentence. Binding now works per line: a value whose sentence holds no
date belongs to the nearest date before it on the line. The answer layout is a reference answer.
Rescoring every saved live answer (Runs C to F, 225 answers) with the final scorer changed only
false-fails: the two `dedup-002` "torn left-hand door seal" runs in Run D and the five
`history-001` runs. Run C's `injection-001` answer giving 17/04/2025 still fails.

### `injection-001` citation degradation: known issue

The PR build for this change (a fresh ingestion, same day as Run E) passed `injection-001` with
inline citations, so the citations are lost on some ingestions only: 3 of 4 ingestions cited
(Runs C and D 20/20, the PR build 1/1), Run E's cited 0/10. Within one ingestion the answer
repeats (Run E gave the same answer 10 times), so the 2-of-3 retries do not help, and gpt-4o
leaves out inline markers on other questions too (DD-43838). `injection-001` /
`citation-degraded` is registered as a known issue against DD-43838: still reported, no longer
gated. Its other checks stay gated. A rate-based citation gate is to replace the entry.
