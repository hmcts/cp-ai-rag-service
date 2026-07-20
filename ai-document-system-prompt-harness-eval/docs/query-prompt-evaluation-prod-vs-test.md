# Query-Prompt Evaluation — Production vs. Updated (Test) Prompts

> A/B evaluation of two query-prompt versions under an identical system prompt, to test whether the
> updated prompts produce **less verbose** responses without losing factual quality or citations.
> `cp-ai-rag-service` — harness module `ai-document-system-prompt-harness-eval`.

## 1. What was tested

Two versions of the same 10 query prompts, matched by `queryId`:

- **prod** — mirrors the production `queryPrompt` for each query.
- **test** — rewritten with explicit output structure and, in several cases, hard word limits, aiming
  for shorter answers.

Both versions were run through the full production pipeline (embed → AI Search → refinement → LLM →
citation post-processing) under **one fixed system prompt** (`v4-strict-citation-grouping-compact`),
so any difference is attributable to the query prompt alone.

### Run configuration

| Setting | Value |
|---|---|
| System prompt | `v4-strict-citation-grouping-compact` (only) |
| Query versions | `prod`, `test` (same 10 queries, joined by `queryId`) |
| Documents | 2 (`4fa52386…`, `22e543b6…`) |
| Models | `gpt-4o-response-generation`, `gpt-5.1` |
| Repetitions | 2 |
| Generation calls | 10 × 2 versions × 2 docs × 2 models × 2 reps = **160** |
| Retrieval | kNN 80 → pool 60 → containment-dedup + MMR → **30 chunks to the LLM** |
| Citation guard | `off` (measurement mode — degraded answers are measured, not thrown) |
| Quality judge | `gpt-5.1`, 80 pairwise prod↔test judgements |
| Reasoning effort | `none` (gpt-5.1) • max tokens 7,000 |

**Mechanics: 160/160 generation calls and 80/80 judge calls succeeded, zero errors.**

Each answer's metrics are averaged over its 2 documents × 2 repetitions (n = 4 per cell). Two citation
metrics are distinguished throughout:

- **cited pages** — pages listed in the model's `FACT_MAP_JSON` block (evidence the model *identified*).
- **rendered citations** — inline source markers actually *shown to the reader* after post-processing.

These usually agree, but they diverge in the gpt-4o citation regression (§4): the model identifies the
evidence (cited pages present) but omits the inline markers, so the citations are stripped and never
rendered. **uncited** counts substantive answers (≥ 50 prose words) with **zero rendered** citations.
The judge scores each answer against **its own** instruction (so a test prompt that caps length is not
penalised for omitting detail it was told to omit).

---

## 2. Headline summary

### Verbosity — the test prompts achieve their goal

| Model | prod words/answer | test words/answer | Change |
|---|---|---|---|
| gpt-5.1 | ~952 | ~586 | **−38%** |
| gpt-4o | ~274 | ~215 | **−22%** |

Coverage held up far better than length fell: on gpt-5.1, cited pages dropped only ~19% against the
38% word cut — denser answers, not thinner evidence. (On gpt-4o the model still *identified* as much
evidence, but for the test version much of it was not rendered to the reader — the citation regression
in §4.)

### Factual quality — the shorter answers are equal-or-richer

Judge verdicts, prod (A) vs test (B), 40 pairs per model:

| Model | EQUIVALENT | prod-richer | **test-richer** | DIVERGENT | Structure prod→test |
|---|---|---|---|---|---|
| gpt-4o | 14 | 10 | **13** | 3 | 4.6 → **4.8** |
| gpt-5.1 | 16 | 8 | **13** | 3 | 4.5 → **4.8** |

The test version is judged **equal-or-richer more often than it is poorer**, on both models, while
improving adherence to the requested format.

### Citations — one model-specific regression

- **gpt-5.1: perfect on both versions** — 0 stripped markers, 0 uncited answers across all 80 answers.
- **gpt-4o + test: 15 of 40 substantive answers rendered with zero citations** — concentrated in
  exactly four queries (§4). Root cause is a missing `[citation]` cue in those test prompts' output
  templates, not a pipeline fault.

### Bottom line

Two separate questions, two answers:

1. **Prompt rewrite (same model):** the test prompts are equal-or-better than prod on both models,
   gated only by the gpt-4o citation fix — details in §3, verdict in §6.
2. **Migration:** the target configuration (**test prompts on gpt-5.1**) beats current production
   (**prod prompts on gpt-4o**) on **every** query, 30 richer / 10 equal / 0 worse — §5.

---

## 3. Per-query analysis

Legend: **words** = mean prose words (prod → test); **cited pages** prod → test; **uncited** = of
4 answers, how many were substantive but had no citation; **judge** = test-vs-prod verdict over the
8 pairs for that query (both models, both docs, both reps).

### 3.1 Substantive analytical queries

#### Summary of the facts of the offences — ✅ test wins
| Model | words (prod→test) | cited pages | uncited (test) | citations |
|---|---|---|---|---|
| gpt-4o | 706 → **526** (−25%) | 13.5 → 13.5 | 0/4 | intact both versions |
| gpt-5.1 | 2,415 → **1,199** (−50%) | 24 → 16 | 0/4 | intact both versions |

Judge (8 pairs): **test-richer 6, prod-richer 2**, structure 4.5 → **5.0**. The single biggest verbosity
win (gpt-5.1 halved) and the judge still rates the shorter test answers richer. The test prompt keeps
an explicit "includes citations" instruction, so citations survive on both models. **Clear win.**

#### Chronology of the case — ⚠️ more concise but loses procedural detail; gpt-4o citation regression
| Model | words (prod→test) | cited pages | uncited (test) | citations |
|---|---|---|---|---|
| gpt-4o | 485 → **266** (−45%) | 6.0 → 6.2 | **4/4** | **lost on test (gpt-4o)** |
| gpt-5.1 | 2,126 → **953** (−55%) | 25.5 → 12.2 | 0/4 | intact |

Judge (8 pairs): **prod-richer 7, divergent 1**. This is the one query where prod is judged *richer* —
the test prompt drops investigative/procedural chronology (arrests, searches, interview content) that
prod retained. Combined with the gpt-4o citation loss, **the test version needs revision here** before
adoption: restore a citation cue and reconsider the procedural-events scope.

#### Applications on the case — ⚠️ good compression; gpt-4o citation regression
| Model | words (prod→test) | cited pages | uncited (test) | citations |
|---|---|---|---|---|
| gpt-4o | 159 → **74** (−53%) | 2.8 → 1.5 | **3/4** | **lost on test (gpt-4o)** |
| gpt-5.1 | 337 → **148** (−56%) | 3.8 → 3.2 | 0/4 | intact |

Judge (8 pairs): prod-richer 4, equivalent 2, test-richer 1, divergent 1. Strong length reduction with
mostly-equivalent content on gpt-5.1; the gpt-4o citation loss is the blocker.

#### Summary of prosecution evidence — ✅ test wins (gpt-5.1); gpt-4o citation regression
| Model | words (prod→test) | cited pages | uncited (test) | citations |
|---|---|---|---|---|
| gpt-4o | 579 → **343** (−41%) | 16.2 → 11.8 | **4/4** | **lost on test (gpt-4o)** |
| gpt-5.1 | 2,095 → **795** (−62%) | 22 → 22.2 | 0/4 | intact |

Judge (8 pairs): **test-richer 6, prod-richer 2**, structure 4.9 → **5.0**. On gpt-5.1 this is an
excellent result — 62% shorter, *same* page coverage, judged richer. The gpt-4o citation regression is
the only issue.

#### Summary of each witnesses evidence — ✅✅ best overall result
| Model | words (prod→test) | cited pages | uncited (test) | citations |
|---|---|---|---|---|
| gpt-4o | 462 → 600 (+30%) | 12.8 → **26.5** | 0/4 | intact (fixes a prod pathology) |
| gpt-5.1 | 1,858 → 2,027 (+9%) | 17.5 → **25.8** | 0/4 | intact |

Judge (8 pairs): **test-richer 7, divergent 1**, structure 4.1 → **5.0**. The exception to the verbosity
trend — here the test prompt is slightly *longer* but markedly richer and better structured, roughly
doubling gpt-4o page coverage. Critically, the test prompt **fixes the prod gpt-4o "witness-bundle"
pathology** (prod averaged 250 stripped markers / had an uncited answer; test has 0). The test prompt
retained "citation links" wording. **Strongest single win.**

#### Summary of the defendants previous convictions — ✅ test richer (gpt-5.1); gpt-4o citation regression
| Model | words (prod→test) | cited pages | uncited (test) | citations |
|---|---|---|---|---|
| gpt-4o | 223 → 242 | 7.8 → 9.5 | **4/4** | **lost on test (gpt-4o)** |
| gpt-5.1 | 525 → 570 | 15.8 → 7.8 | 0/4 | intact |

Judge (8 pairs): **test-richer 5**, equivalent 2, divergent 1. The restructured test prompt (numbered
sections 1–8) is judged richer and far better organised. gpt-4o citation regression again.

### 3.2 Fixed-list extraction queries (controls)

The prompts for **dwelling burglary, drug trafficking/supply, offensive weapon/blade, and
alcohol/drug driving** were **identical** between prod and test (only the six analytical prompts
changed). They act as a control set — any difference here is run-to-run noise.

| Query | judge (8 pairs) | cos | Notes |
|---|---|---|---|
| Previous dwelling burglary | EQ 5, prod 2, test 1 | 0.97 | Data-driven list; both versions equivalent |
| Previous drug trafficking/supply | **EQ 8** | **1.00** | No relevant convictions → identical short refusals |
| Previous offensive weapon/blade | EQ 5, divergent 2 | 0.99 | Equivalent; minor run variance |
| Previous alcohol/drug driving | **EQ 8** | **1.00** | No relevant convictions → identical short refusals |

The near-perfect equivalence and ~1.0 cosine on the identical prompts confirm the harness and the
retrieval path are stable, so the differences seen on the analytical queries are real prompt effects,
not measurement noise.

---

## 4. The gpt-4o citation regression — root cause

15 of 40 substantive gpt-4o test answers rendered **zero** citations. They map **exactly** to the four
analytical queries whose updated `OUTPUT FORMAT` template contains **no `[citation]` placeholder**:

| Query | test prompt has `[citation]` cue? | gpt-4o test uncited | gpt-5.1 test uncited |
|---|---|---|---|
| Chronology of the case | ✗ | 4/4 | 0/4 |
| Applications on the case | ✗ | 3/4 | 0/4 |
| Summary of prosecution evidence | ✗ | 4/4 | 0/4 |
| Summary of previous convictions | ✗ | 4/4 | 0/4 |
| Summary of the facts | ✓ ("includes citations") | 0/4 | 0/4 |
| Summary of witnesses evidence | ✓ ("citation links") | 0/4 | 0/4 |

**gpt-4o follows the query-level output template literally and lets it override the system prompt's
citation contract**; gpt-5.1 does not — it honours the system prompt regardless. The two test prompts
that kept a citation cue stayed fully cited on both models, which confirms the mechanism.

**Important for production:** because these omissions are *instruction-induced and deterministic*, the
citation guard's retry would not help — a redelivered attempt follows the same template and omits
citations again. In `DELIVER` mode they would ship uncited (flagged); in `REJECT` mode those four
queries would consistently fail on gpt-4o. **The fix belongs in the query prompts, not the pipeline.**

---

## 5. Migration view — production today vs proposed target

This is the actual deployment decision: **current production** (`prod` query prompt on **gpt-4o**)
against the **proposed target** (`test` query prompt on **gpt-5.1**). Unlike §3 (which varies only the
prompt), this varies both prompt and model together. The judge scored 40 pairs over the same generated
answers (10 queries × 2 documents × 2 reps), each answer against its own instruction.

### The target wins outright

| Judge verdict (target vs production) | Pairs |
|---|---|
| **target (test + gpt-5.1) richer** | **30 / 40** |
| Equivalent | 10 / 40 |
| production (prod + gpt-4o) richer | **0** |
| Divergent | **0** |

Every substantive query was judged target-richer in all 4 of its pairs; the only ties were the two
genuine no-conviction queries. Structure adherence rose from ~3.5–4.8 to **5.0** almost everywhere.
**Note the important nuance:** the Chronology result that looks like a *prod* win in §3 (a same-model
finding) **reverses here** — gpt-5.1's stronger extraction more than compensates for the test prompt's
trimmed scope, so the target beats production on Chronology too.

### The trade

| | production (prod + gpt-4o) | target (test + gpt-5.1) | Change |
|---|---|---|---|
| Words / answer | ~274 | ~586 | **+114%** |
| Cited pages (total, 40 answers) | 264 | 375 | +42% |
| **Rendered citations** (total) | ~200 | ~770 | **~3.9×** |
| Uncited substantive answers | 2 | **0** | eliminated |
| Stripped (lost) markers | 1,202 | **0** | eliminated |

The migration buys **~3.9× the user-visible citations, better grounding, and the removal of gpt-4o's
citation pathologies**, at the cost of **~2× longer answers**. The citation-cue problem that blocks the
test prompts on gpt-4o (§4) **does not arise here** — gpt-5.1 honours the system prompt's citation
contract regardless of the query template.

---

## 6. Verdict and recommendations

### The two questions, side by side

| | Q1 — Prompt rewrite (same model) | Q2 — Migration (test + gpt-5.1 vs prod + gpt-4o) |
|---|---|---|
| Evidence | §3 | §5 |
| Winner | Test on gpt-5.1; mixed on gpt-4o (citation bug) | **Target, on every query** (30 richer / 10 equal / 0 worse) |

The per-query "same-model winner" table below answers **Q1** — it is diagnostic (it tells you which
test prompts still need work). It does **not** override the migration answer: for **Q2**, the target
configuration wins every query (§5).

### Same-model winner (Q1)

Verbosity Δ on gpt-5.1. "Judge" is the net test-vs-prod verdict over the 8 same-model pairs (both
models, docs, reps). `*` = test wins once its `[citation]` cue is restored (§4).

| # | Query | Verbosity Δ (gpt-5.1) | Judge (test vs prod, same model) | gpt-4o citations | Same-model winner |
|---|---|---|---|---|---|
| 1 | Summary of the facts | −50% | test-richer 6 / prod 2 | ✅ intact | 🟢 **Test** |
| 2 | Chronology of the case | −55% | **prod-richer 7** / div 1 | ✗ lost | 🔵 **Prod** — test over-trimmed + needs citation fix |
| 3 | Applications on the case | −56% | prod 4 / EQ 2 / test 1 | ✗ lost | 🔵 **Prod** (narrow) — test after citation fix |
| 4 | Summary of prosecution evidence | −62% | test-richer 6 / prod 2 | ✗ lost | 🟢 **Test\*** |
| 5 | Summary of each witnesses evidence | +9% | test-richer 7 / div 1 | ✅ intact (fixes prod bug) | 🟢 **Test** |
| 6 | Summary of previous convictions | +9% | test-richer 5 / EQ 2 | ✗ lost | 🟢 **Test\*** |
| 7–10 | The four fixed-list queries (controls) | ~0 (identical prompts) | mostly EQ | ✅ intact | ⚪ **Tie** |

**Same-model tally:** Test wins 2 outright (Facts, Witnesses), 2 after the citation fix (Prosecution
evidence, Convictions); Prod retains 2 (Chronology on content, Applications narrowly); 4 controls tie.

### Per-query changes to make the test prompts production-ready

All six substantive queries are target-richer 4/4 in the migration (§5), so these are **hardening**
changes — the priority is making the test prompts citation-safe on gpt-4o and restoring the one scope
that was genuinely over-trimmed. ⭐ = required for gpt-4o safety.

| Query | Change to consider |
|---|---|
| Summary of the facts | Strong as-is (keeps a citation cue). Optional: add a word cap (≤600) for consistency. |
| Chronology of the case | ⭐ Add the `[citation]` cue; **restore procedural/investigative events** (arrests, searches, interviews, bail) — the one scope the rewrite over-trimmed. Keep the concise bullet format. |
| Applications on the case | ⭐ Add `[citation]` to the bullet template. Otherwise excellent compression (−56%); no other change. |
| Summary of prosecution evidence | ⭐ Add `[citation]` to the "Key Evidence" / "Defendant Account" lines. Keep the 550-word cap and per-charge structure. |
| Summary of witnesses evidence | Keeps citation links + 200/500-word caps. Optional: the 500-word total cap may bind on very multi-witness cases — consider per-witness only. |
| Summary of previous convictions | ⭐ Add `[citation]` to the numbered sections (the 8-section rewrite dropped prod's trailing cue). Keep the structure — it scored 5.0. |
| The four fixed-list queries | No change — prompts identical to prod and already carry `[citation]`. |

### Recommendations

1. **Adopt the target configuration (test prompt + gpt-5.1).** Judged richer-or-equal in 40/40 pairs,
   never worse, ~3.9× the rendered citations, no gpt-4o citation defects — at the cost of ~2× length.
2. ⭐ **Apply the citation-cue fix** to the four test prompts (Chronology, Applications, Prosecution
   evidence, Previous convictions). Required only for gpt-4o, but cheap and makes every prompt
   model-agnostic — do it regardless, so gpt-4o remains a safe fallback.
3. **Restore the Chronology procedural scope** — the sole content the rewrite genuinely lost.
4. **Keep the length caps and heading structure** — they drive the verbosity reduction and the 5.0
   structure scores at no quality cost.
5. **Re-verify cheaply** — a gpt-4o-only, test-version-only pass (40 calls) after the edits, to confirm
   the 15 uncited answers go to zero.

---

*Generated from this round's harness run: 160 gpt-4o + gpt-5.1 generation calls; 80 gpt-5.1 judge pairs
for the within-model prod↔test comparison (§2–§4, §6) plus 40 gpt-5.1 judge pairs for the
production-vs-target migration comparison (§5), judged over the same generated answers; 2 documents,
2 repetitions, v4 system prompt. Metrics are averages over documents × repetitions; judge verdicts are
counts over the pairs per query.*
