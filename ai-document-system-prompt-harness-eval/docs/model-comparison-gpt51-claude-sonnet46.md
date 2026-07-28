# Model comparison — GPT-5.1 vs Claude Sonnet 4.6 (Azure AI Foundry)

> Results of the head-to-head model evaluations using the harness's model-comparison dimension
> (`ResponseQualityComparator`, model A vs B per (query, prompt, iteration) — identical system
> prompt, query instruction and retrieved chunks on both sides, so differences are attributable
> to the model alone). Two runs: **Run 1** (14 July 2026, 2 documents, sequential) and
> **Run 2** (28 July 2026, 5 IDPC documents, parallel model streams). Companion to
> `claude-sonnet-azure-feasibility.md` (integration + caveats) and `idpc-document-ids.md`
> (Run 2 corpus).

## 1. Configuration (common to both runs)

- **Models:** `gpt-5.1` (Azure OpenAI, `reasoning_effort=none`) vs `claude-sonnet-4-6`
  (Azure AI Foundry via the Anthropic Messages API, thinking off, no sampling params).
- **Prompt:** `v4-strict-citation-grouping-compact`; **queries:** the 10-query
  `user-queries-model-test.json` set (single `test` version, explicit output-size caps);
  1 repetition; `CITATION_GUARD_MODE=off`; **judge:** gpt-5.1.
- Run 1: 2 case documents → 20 rows/model, sequential model streams.
- Run 2: 5 IDPC documents (`idpc1–idpc5`, see `idpc-document-ids.md`) → 50 rows/model,
  **parallel model streams** (one worker per model, sequential within each stream).

## 2. Quality (judge verdicts, paired rows)

| Aggregate | Run 1 (20 rows) | Run 2 (49 rows¹) |
|---|---|---|
| B_RICHER (Claude) | 10 (50%) | **28 (57%)** |
| A_RICHER (GPT-5.1) | 5 (25%) | 7 (14%) |
| EQUIVALENT | 2 | 9 |
| DIVERGENT | 3 | 5 |
| Mean structure adherence (A / B) | 4.5 / 4.5 | 4.5 / **4.6** |
| Mean prose-embedding cosine | 0.8460 | 0.8432 |

¹ One Claude cell lost to a transient network connection reset (see §5) — 49 of 50 pairs judged.

The Run 1 reading holds and strengthens over the wider corpus: Claude is judged factually
richer on the majority of rows **while writing ~35% less prose**, with format adherence now
slightly ahead. The Run 1 caveats also still apply: a share of Claude's B_RICHER verdicts on
strict `if_not_found` extraction queries reflect *unrequested* added context (richer but less
compliant), and GPT-5.1 remains stronger on witness coverage.

## 3. Citation contract (raw model output)

| Metric (Run 2, 50 rows/model) | gpt-5.1 | claude-sonnet-4-6 |
|---|---|---|
| Answers generated | 50/50 | 49/50 (1 network error) |
| Parseable `<FACT_MAP_JSON>` | 50/50 | 49/49 |
| Inline⇄JSON `match` | 37/50 (74%) | **45/49 (92%)** |
| Mean same-document stacked runs | 5.0 | **0.5** |
| Mean distinct citations / row | 6.7 | 3.7 |
| Mean prose (chars / words) | 3,336 / 537 | 2,150 / 344 |

Same shape as Run 1 at larger scale: Claude honours the citation contract far more reliably in
raw output (10× less same-document stacking — GPT-5.1 leans heavily on the deterministic
`CitationProcessor` merge) and stays inside the prompts' length caps. GPT-5.1 cites more
distinct sources per answer, consistent with its broader-coverage/longer-answer style.

## 4. Performance

**Per-call generation latency** (comparator, paired rows):

| Run | gpt-5.1 mean (min–max) | claude-sonnet-4-6 mean (min–max) |
|---|---|---|
| Run 1 | 14.8 s (1.4–50.2) | 20.5 s (2.0–58.9) |
| Run 2 | 15.0 s (1.5–66.5) | 18.7 s (2.0–56.5) |

Stable across runs: Claude is ~15–40% slower per answer on average, dominated by the short
extraction queries; on the heaviest summaries the two are comparable.

**Wall-clock — parallel model streams (Run 2):** 100 generation calls completed in
**19.5 minutes** (14:16:41 → 14:36:11). The stream spans were gpt-5.1 ≈ 17.7 min and
Claude ≈ 19.4 min; run sequentially those sum to ≈ 37 minutes, so parallelism delivered a
**≈1.9× generation-phase speedup**, bounded by the slower (Claude) stream as designed.
End-to-end run (build + retrieval + generation + 49 judge calls): 27m 49s.

## 5. Reliability note

One Claude call failed with a transport-level `Connection reset` (not a model or contract
failure); the harness recorded the cell as ERROR, the stream continued, and the comparator
skipped the unpaired row. Expected behaviour; at this failure rate (1/100) no retry logic is
warranted — re-run the affected cell if its row matters.

## 6. Conclusions and next steps

The five-document run confirms Run 1 rather than revising it: **Claude Sonnet 4.6 delivers
richer substantive answers with materially better citation discipline and length-cap
adherence, at a modest per-call latency premium**; GPT-5.1 keeps the edge on short-query
speed and witness coverage, and follows literal-sentinel instructions more exactly. Verdicts
come from a gpt-5.1 judge, so family bias would favour GPT-5.1 — strengthening, not
weakening, the Claude-favourable majority.

Recommended follow-ups:
1. Prompt nudges for the two persistent Claude weaknesses (witness coverage under tight caps;
   sentinel-only output on `if_not_found` extractions), then re-run.
2. A 2–3 repetition run for verdict stability now that parallel streams make the cost of
   repetitions tolerable.
3. Manual IDPC spot-check of a sample of B_RICHER rows (standing requirement) before any
   adoption recommendation.

*Raw logs: `gpt51-claude-run.log` (Run 1), `parallel-run.log` (Run 2).*
