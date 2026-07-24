# Model comparison — GPT-5.1 vs Claude Sonnet 4.6 (Azure AI Foundry)

> Results of the first head-to-head model evaluation run of 14 July 2026, using the harness's
> model-comparison dimension (`ResponseQualityComparator`, model A vs B per (query, prompt,
> iteration) — identical system prompt, query instruction and retrieved chunks on both sides,
> so differences are attributable to the model alone). Companion to
> `claude-sonnet-azure-feasibility.md` (integration + caveats).

## 1. Run configuration

- **Models:** `gpt-5.1` (Azure OpenAI, `reasoning_effort=none`) vs `claude-sonnet-4-6`
  (Azure AI Foundry via the Anthropic Messages API, thinking off, no sampling params).
- **Matrix:** 10 queries (`user-queries-model-test.json`, single `test` version with explicit
  output-size caps) × 2 case documents × 1 repetition × 1 prompt
  (`v4-strict-citation-grouping-compact`) = 40 generation calls.
- **Judge:** gpt-5.1 (`CITATION_GUARD_MODE=off`; 20 pairwise model judgements).
- **Outcome:** zero call failures, zero refusals, zero truncations on either model.

## 2. Quality (judge verdicts, 20 paired rows)

| Aggregate | Value |
|---|---|
| Verdicts | **B_RICHER (Claude) 10** • A_RICHER (GPT-5.1) 5 • DIVERGENT 3 • EQUIVALENT 2 |
| Mean structure adherence (1–5, own instruction) | A 4.5 • B 4.5 — dead even |
| Mean prose-embedding cosine | 0.8460 |

The split has structure that matters more than the headline:

- **Substantive summaries** (offence facts, chronology, prosecution evidence,
  previous-convictions summary): Claude was mostly judged factually richer *while writing
  less* — it added specific evidential detail and honoured word caps better (judgeStruct
  B5 vs A4 on several rows).
- **Strict `if_not_found` extraction queries:** most Claude B_RICHER verdicts here are
  double-edged. GPT-5.1 emitted the literal sentinel exactly as instructed (judgeStruct 5);
  Claude added unrequested PNC context — "richer" but *less* compliant (judgeStruct 4).
  Richer ≠ better on these tasks.
- **Witness summaries — GPT-5.1's clearest win:** it covered many more witnesses (A_RICHER
  on both documents); Claude summarised a subset, likely over-honouring the 500-word cap at
  the cost of coverage.
- Judge-bias note: the judge is gpt-5.1 judging its own family vs Claude; a family bias would
  favour A, which strengthens rather than weakens the B_RICHER majority.

## 3. Citation contract (raw model output)

| Metric | gpt-5.1 | claude-sonnet-4-6 |
|---|---|---|
| Answers generated / parseable `<FACT_MAP_JSON>` | 20/20 / 20/20 | 20/20 / 20/20 |
| Inline⇄JSON `match` | 14/20 | **18/20** |
| Same-document stacked runs | heavy (up to 22 per answer) | **minimal (0–6)** |
| Uncited substantive answers | 0 | 0 |

Both models' `match` misses are dominated by the no-findings pattern (bare sentinel answer +
empty JSON, non-match by definition). Claude cited a source on several no-findings rows where
GPT-5.1 returned an uncited sentinel, and follows the citation-grouping rule far better —
GPT-5.1 leans heavily on the deterministic `CitationProcessor` merge.

## 4. Performance (generation latency, paired rows)

| Model | Mean | Min | Max | Character |
|---|---|---|---|---|
| gpt-5.1 | **14.8 s** | 1.4 s | 50.2 s | Very fast on short extractions (1.4–6 s); slow on long summaries |
| claude-sonnet-4-6 | 20.5 s | 2.0 s | 58.9 s | 2–4× slower on short extractions; comparable, sometimes faster, on the heaviest rows |

Verbosity inverted vs the earlier GPT-4o smoke test: under the capped test prompts Claude
consistently wrote **shorter** answers than GPT-5.1 (witness summaries ~3.3–6k chars vs
~11–14k). Claude's earlier 2–4× verbosity appears only when the query prompt carries no
length constraint.

## 5. Conclusions and next steps

Claude Sonnet 4.6 is a **credible candidate** on this pipeline: better raw citation
discipline, better length-cap adherence, factually richer on most substantive summaries —
as judged by GPT-5.1 itself. Its two weaknesses (witness coverage under tight caps;
over-elaboration on strict `if_not_found` extractions) are both prompt-addressable.
GPT-5.1 keeps the edge on short-query speed and exhaustive witness coverage.

Recommended follow-ups:
1. Re-run at 2–3 repetitions for stability, focusing on the 3 DIVERGENT rows and the witness
   queries (single-repetition rows must be read as indicative, not conclusive).
2. Trial a Claude-tuned nudge: "cover every witness" weighting against the word cap, and
   "output the sentinel only, no additional context" on extraction queries.
3. Manual spot-check of 2–3 B_RICHER rows against the IDPC (the cross-model doc's standing
   requirement) before drawing adoption conclusions.

*Raw log: `gpt51-claude-run.log` (harness run of 14 July 2026, ~19 min end-to-end).*
