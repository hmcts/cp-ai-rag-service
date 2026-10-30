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
