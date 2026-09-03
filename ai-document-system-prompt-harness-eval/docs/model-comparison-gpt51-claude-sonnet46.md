# Model Comparison — GPT-5.1 vs Claude Sonnet 4.6 (Azure AI Foundry)

> **Definitive** head-to-head model evaluation via the harness's model-comparison axis
> (`ResponseQualityComparator`, model A vs B per (query, document, iteration) — identical system
> prompt, query instruction and retrieved chunks on both sides, so any difference is attributable to
> the **model alone**). Supersedes the earlier draft (two exploratory runs, 14/28 July). Companion to
> `claude-sonnet-azure-feasibility.md` (integration + caveats).
>
> `cp-ai-rag-service` — harness module `ai-document-system-prompt-harness-eval`.

---

## 1. Run configuration (baseline)

Reproduce by restoring these in `.env` and running `./run-harness.sh`. Treat as the baseline for
future model comparisons — hold fixed unless a knob is the thing under test.

| Setting | Value |
|---|---|
| Models (`HARNESS_LLM_DEPLOYMENTS`) | `gpt-5.1` (Azure OpenAI) vs `anthropic:claude-sonnet-4-6` |
| Claude routing | per-model `@endpoint` (Azure AI Foundry, Anthropic Messages API, `…services.ai.azure.com/anthropic`) set in `.env`; auth via `DefaultAzureCredential` (no API key) |
| gpt-5.1 | `reasoning_effort=none`; temperature/top_p omitted (reasoning model) |
| claude-sonnet-4-6 | thinking **off**, no sampling params (Claude 4.x rejects temperature+top_p together) |
| System prompt | `v4-strict-citation-grouping-compact` (only) |
| Query set (`HARNESS_QUERY_FILE`) | `user-queries-model-test.json` — single `test` version, 10 queries carrying the **tightened release-candidate prompts** (hard word caps; aligned with the prod-vs-test `test` set). Updated in this PR — see §7 for the delta vs the draft's older prompts. |
| Documents | 5 IDPC case documents (full IDs below) |
| Repetitions | 1 (aggregation over the 5 documents, n = 5 per cell) |
| Model streams | **parallel** (one worker per model, sequential within each stream) |
| Citation guard | `off` (measurement mode) |
| Retrieval sizing | kNN 80 / pool 60 / MMR-final 30, λ 0.8; containment-dedup on, legacy dedup off |
| HTTP timeouts | response 600s / read 300s (gpt-5.1; Claude uses the Anthropic SDK's own defaults — 10-min timeout, 2 retries) |
| Judge | `gpt-5.1` (see the self-preference caveat, §6) |

**Documents (`HARNESS_DOCUMENT_IDS`) — full IDs for reproducibility:**

```
349ef56e-d211-45a5-aa06-5dcc2929b6e9
50b5da6c-ad06-43b1-a798-8ffba4bda4a3
b7fcebd1-0ee3-4908-9215-769d9aebdd28
c9bcfab7-2fe3-4b00-8128-26371c6d58ba
3f326c23-61d1-46ab-91d0-88e4d7a8614a
```

**Volume executed (all succeeded — zero errors / timeouts / skips):** 50 retrieval embeddings + 50 AI
Search queries (shared across models); **100 generation calls** (50 gpt-5.1 ∥ 50 Claude); **50 judge
pairs** (model axis; single prompt + single query version, so the version/prompt/cross-cut axes do not
fire). **This run had no lost cells** (the draft's second run lost one Claude cell to a transient
network reset — 49/50; here it is a clean 50/50).

---

## 2. Headline — quality (judge verdicts, 50 paired rows)

A = `gpt-5.1`, B = `claude-sonnet-4-6`. Each answer judged against its own (identical) instruction.

| Verdict | Pairs |
|---|---|
| **Claude richer** (B_RICHER) | **21 (42%)** |
| gpt-5.1 richer (A_RICHER) | 8 (16%) |
| Equivalent | 17 (34%) |
| Divergent | 4 (8%) |
| Mean structure adherence (A / B) | **4.8 / 4.7** |
| Mean prose-embedding cosine | 0.9219 |

**On the rows the judge decided (excluding the 17 ties), Claude wins 21 to 8 — ~72%.** The one-third
Equivalent share is concentrated in the fixed-list extraction queries (§3), where both models return
essentially the same short list. Structure adherence is effectively tied, marginally to gpt-5.1.

---

## 3. Per-query breakdown

Verdicts over the 5 documents for each query (A = gpt-5.1, B = Claude):

| Query | Result (5 docs) | Read |
|---|---|---|
| Summary of the facts | 4 B · 1 EQ | 🟢 Claude |
| Chronology of the case | 2 A · 2 B · 1 DIV | ⚪ Split |
| Applications on the case | 3 B · 1 A · 1 DIV | 🟢 Claude |
| Summary of prosecution evidence | 3 B · 1 A · 1 DIV | 🟢 Claude |
| **Summary of each witnesses evidence** | **4 A · 1 B** | 🔵 **gpt-5.1** |
| Summary of previous convictions | 4 B · 1 EQ | 🟢 Claude |
| Previous dwelling burglary | 3 EQ · 1 B · 1 DIV | ⚪ Tie |
| Previous drug trafficking/supply | 5 EQ | ⚪ Tie |
| Previous offensive weapon/blade | 3 EQ · 2 B | ⚪ Tie (lean Claude) |
| Previous alcohol/drug driving | 4 EQ · 1 B | ⚪ Tie |

Two clear signals plus a caveat, all consistent with the draft:
- **Claude is richer on the substantive analytical summaries** (facts, convictions, applications,
  prosecution evidence).
- **gpt-5.1 owns witness evidence** (4/5) — its broader-coverage style captures more witnesses under
  the tight per-witness caps.
- The four **fixed-list extraction** queries are mostly Equivalent (drug 5/5, driving 4/5). Several of
  Claude's scattered B_RICHER verdicts on these reflect **unrequested added context** — richer to the
  judge, but arguably *less* compliant with the strict `if_not_found` / list-only instruction (a
  standing caveat for manual spot-check before any adoption call).

---

## 4. Citation contract & verbosity (raw model output, 50 rows/model)

| Metric | gpt-5.1 | claude-sonnet-4-6 |
|---|---|---|
| Answers generated | 50/50 | 50/50 |
| Parseable `<FACT_MAP_JSON>` | 50/50 | 50/50 |
| Inline ⇄ JSON `match` | 45/50 (90%) | **49/50 (98%)** |
| Mean same-document stacked runs | 4.4 | **0.4** |
| Mean distinct citations / row | 7.8 | 3.1 |
| Mean prose (chars / words) | 2,884 / 450 | **1,820 / 287** |
| Rendered citations (total) | 591 | 401 |
| Stripped (unresolved) markers (total) | 100 | **0** |
| Uncited substantive answers | 0 | 0 |

Same shape as the draft, at a clean 50/50: **Claude honours the citation contract far more reliably in
raw output** — ~**11× less same-document stacking** (0.4 vs 4.4; gpt-5.1 leans on the deterministic
`CitationProcessor` merge to clean up its stacks), a higher inline⇄JSON match rate, and **zero stripped
markers** (vs 100 for gpt-5.1). It also stays well inside the prompts' length caps (**~36% less prose**:
287 vs 450 words). gpt-5.1 cites more distinct sources per answer (7.8 vs 3.1), consistent with its
broader-coverage / longer-answer style and its witness-query strength.

---

## 5. Performance

**Per-call generation latency** (comparator, paired rows):

| | mean | min–max |
|---|---|---|
| gpt-5.1 | 8.8 s | 1.2 – 46.4 |
| claude-sonnet-4-6 | 16.5 s | 3.6 – 65.0 |

Claude is ~**1.9× slower per answer** on average, widest on the short extraction queries and narrowing
on the heavy summaries.

**Wall-clock (parallel model streams):** generation phase ≈ **17.9 min** (gpt-5.1 stream ≈ 11.4 min,
Claude stream ≈ 17.9 min); run sequentially those sum to ≈ 29 min, so parallelism gave a **≈1.6×
generation-phase speedup**, bounded by the slower (Claude) stream as designed. End-to-end (build +
retrieval + 100 generation + 50 judge calls) ≈ **25 min**.

---

## 6. Reliability & judge caveat

- **Reliability:** 0 errors across all 100 generation + 50 judge calls — a clean run (the draft's
  wider run hit one transport-level `Connection reset` on a Claude call; none here).
- **Judge self-preference:** verdicts come from a **gpt-5.1** judge, so any model-family bias favours
  gpt-5.1. That bias runs *against* the observed result — so the Claude-favourable majority is, if
  anything, **understated**, not inflated.

---

## 7. Consistency with the draft runs

| | Draft Run 2 (28 Jul, 49 pairs) | This run (50 pairs) |
|---|---|---|
| Claude richer | 28 (57%) | 21 (42%) |
| gpt-5.1 richer | 7 (14%) | 8 (16%) |
| Equivalent / Divergent | 9 / 5 | 17 / 4 |
| Structure (A / B) | 4.5 / 4.6 | 4.8 / 4.7 |
| Same-doc stacking (gpt-5.1 / Claude) | 5.0 / 0.5 | 4.4 / 0.4 |
| Inline⇄JSON match (gpt-5.1 / Claude) | 74% / 92% | 90% / 98% |
| Prose words (gpt-5.1 / Claude) | 537 / 344 | 450 / 287 |
| Latency (gpt-5.1 / Claude) | 15.0 s / 18.7 s | 8.8 s / 16.5 s |

Same qualitative conclusion. The Claude margin is **narrower** here (more Equivalents: 17 vs 9),
but the direction is unchanged — Claude wins the decided rows ~72% (21:8). The **citation-discipline
and length-cap story is identical and strong**. The higher prose-embedding cosine (0.92 vs 0.84) means
the two models' answers were more alike this run; gpt-5.1 also cleaned up its inline⇄JSON match (90% vs
74%). Structure adherence is a wash in both runs.

**One methodological difference to keep in mind.** The draft runs used the *older, verbose*
`user-queries-model-test.json` prompts; this definitive run uses the **tightened release-candidate
prompts** (hard word caps, "cite once per event" — the same set adopted in the prod-vs-test work).
So part of the cross-run shift — Claude's shorter prose, the higher equivalence rate, and gpt-5.1's
improved inline⇄JSON match — reflects the stricter prompts, not the models alone. The **head-to-head
itself is unaffected**: both models receive the identical new prompt on every row, so the model-vs-model
comparison remains clean; only the delta *against the draft* carries this caveat.

---

## 8. Cost comparison

List rates (per million tokens, USD, Azure Global Standard — verified 4 Aug 2026, re-verified
3 Sep 2026, unchanged):

| | Input | Output | Notes |
|---|---|---|---|
| claude-sonnet-4-6 (Foundry, CCU) | **$3.00** | **$15.00** | CCU billing converts tokens at Anthropic's published per-model rates — CCU is a billing format, not a price change |
| gpt-5.1 (Azure OpenAI) | **~$1.38** | **~$11.00** | confirm against the invoice; trackers and the Azure pricing page agree |

Raw ratios: Claude is **2.2× on input, 1.4× on output**. Neither headline number is the effective
premium for this workload, for two offsetting reasons: the pipeline is **input-heavy** (~85–90% of
tokens are retrieved chunks + prompts), which pushes toward the 2.2× input ratio — but **gpt-5.1
writes ~55% more prose than Claude** (450 vs 287 words in this run) and output is the expensive
dimension, which claws a chunk back (gpt-5.1's tokenizer also counts the same input ~10–15% smaller).

Modelled on the observed Claude billing for the evaluation work (£4.04 for 784k tokens, an 85/15
input/output split, list rates ex-VAT):

| | Claude Sonnet 4.6 | gpt-5.1 equivalent |
|---|---|---|
| Input | ~666k × $3 ≈ $2.00 | ~583k × $1.38 ≈ $0.80 |
| Output | ~118k × $15 ≈ $1.77 | ~177k × $11 ≈ $1.95 |
| **Total (list, ex-VAT)** | **≈ $3.75 (~£2.80)** | **≈ $2.75 (~£2.05)** |

**Effective premium for Claude on this workload: ~1.4–1.8×** (the gpt-5.1 side costs roughly 55–75%
of the Claude side; the observed £4.04 maps to ~£2.20–£2.90 on gpt-5.1). Per run the absolute
amounts are trivial — the premium becomes a decision factor only at production volumes.

Caveats and the main lever:
- The observed blended rate (~£5.15/MTok) sits above the list-derived ~£3.60/MTok — VAT on the
  Marketplace meter, USD→GBP conversion and/or a higher output share than the modelled 15% account
  for the gap; the Foundry Monitoring tab's per-model token splits would make the arithmetic exact.
- gpt-5.1 ran at `reasoning_effort=none`; higher effort adds hidden reasoning tokens billed as
  output, raising its side.
- **Prompt caching is Claude's big untapped lever**: cache reads bill at 0.1× ($0.30/MTok), and the
  harness resends the identical system prompt and chunks on every repetition uncached. For
  multi-repetition runs, caching could cut Claude's dominant input cost by ~70–90%, largely erasing
  the premium.

---

## 9. Conclusions & next steps

This definitive run **confirms the draft rather than revising it**: **Claude Sonnet 4.6 produces richer
substantive summaries with materially better citation discipline and length-cap adherence, at a ~1.9×
per-call latency premium**; **gpt-5.1 keeps the edge on witness coverage** and follows literal-sentinel
extraction instructions more exactly. The gpt-5.1 judge biases toward gpt-5.1, strengthening the
Claude-favourable reading. On cost (§8), Claude carries a **~1.4–1.8× effective premium** for this
input-heavy workload at current list rates — a consideration rather than a blocker, and one that
prompt caching would largely erase.

Recommended follow-ups:
1. **Prompt nudges** for the two persistent Claude weaknesses — witness coverage under tight caps, and
   sentinel-only output on `if_not_found` extractions — then re-run.
2. A **2–3 repetition** run for verdict stability, now that parallel streams make repetitions cheap.
3. **Manual IDPC spot-check** of a sample of B_RICHER rows (standing requirement) before any adoption
   recommendation — particularly the fixed-list "richer = unrequested context" cases.
4. **Enable Anthropic prompt caching in the harness** before the repetition run — it addresses the
   dominant cost term (§8) and makes multi-repetition Claude runs near-free on the input side.

---

*Generated from a single harness run: 100 generation calls (50 gpt-5.1 ∥ 50 claude-sonnet-4-6) and 50
gpt-5.1 model-axis judge pairs, over 5 documents, 1 repetition, v4 system prompt,
`user-queries-model-test.json`, `CITATION_GUARD_MODE=off`. Metrics are aggregates over the 5 documents;
judge verdicts are counts over the 50 pairs. Cosine is a divergence flag, not a quality score.*
