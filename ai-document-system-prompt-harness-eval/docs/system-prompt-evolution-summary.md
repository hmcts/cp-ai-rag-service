# Answer-Retrieval System Prompt — Evolution v1–v5 and Evaluation Outcomes

> Evolution v1 → v5, supporting pipeline changes, and evaluation outcomes.
> `cp-ai-rag-service` — 18 June 2026; v5 added 28 October 2026 (§7)

## 1. Purpose and context

The answer-retrieval function generates cited answers over case documents. Its system prompt
(`RESPONSE_GENERATION_SYSTEM_PROMPT`) governs the citation contract: inline numbered placeholders
(`[1]`, `[2]`…) plus a machine-readable `<FACT_MAP_JSON>` block mapping each placeholder to a
document and its supporting pages. Production runs GPT-4o; a move to the GPT-5.1 reasoning model is
under evaluation, so every prompt change is validated cross-model with an offline evaluation harness
(`ai-document-system-prompt-harness-eval`) that drives the real production pipeline (embeddings →
AI Search → chunk formatting → LLM → citation post-processing) and scores citation compliance,
verbosity, coverage and — newly — response quality.

## 2. Prompt lineage

| Version | File (`prompts/`) | ≈Tokens | What changed and why |
|---|---|---|---|
| **v1** | `v1-baseline-production.txt` | 900 | The original deployed prompt. Single-placeholder rule and a dual page-number schema (`pageNumbers` + `individualPageNumbers`). Observed failures: bracket-spray (`[1]…[29]`), citationIds derived from document GUIDs, inconsistent grouping. |
| **v2** | `v2-baseline-with-improvements.txt` | 1,678 | Cross-model rework for the prospective GPT-5.1 move: citationId redefined as a plain sequential counter (never GUID-derived), explicit description of the retrieved-document XML structure, WRONG/RIGHT worked examples, schema simplified to `individualPageNumbers` only. Validated across GPT-4o and GPT-5.1 on both SDK paths (Azure & OpenAI). |
| **v3** | `v3-strict-citation-grouping.txt` | 2,099 | Targeted *same-document citation stacking* (adjacent `[1][2][3]` all resolving to one document instead of one grouped citation): absolute adjacency rule (adjacent brackets only for *different* documents), consecutive-sentence grouping guidance, a pre-emit self-check, a stacking WRONG/RIGHT example pair. Also removed the trailing "no relevant evidence" example after the team observed it biased models toward spurious "no information" answers (recency effect). |
| **v4** | `v4-strict-citation-grouping-compact.txt` | **993 (−53% vs v3)** | Size optimisation. A review found ~45–50% of v3 was defensive text whose failure modes are now *deterministically handled in code* (§3). v4 keeps the full citation contract, document structure, output schema, heading rules and the three highest-value examples; it drops the self-check rule, four redundant examples, the malformed-bracket catalogue and duplicated style directives. The system prompt is sent on every call, so the saving (~1,100 tokens/call) is permanent. |
| **v5** | `v5-domain-neutral.txt` | ≈1,200 | Domain-neutral interim prompt (DD-43890): no persona, domain spelling examples or domain few-shot examples — domain framing belongs in each client's query prompt. Adds grounding (no outside knowledge or opinion), precedence (the query instruction cannot change the citation rules), retrieved text as data never instructions, and rule 8 (the `FACT_MAP_JSON` block and inline `[N]` survive any prescribed answer format). Citation rules 1–7 unchanged from v4. See §7. |

## 3. Supporting pipeline changes (code, not prompt)

- **`CitationProcessor` same-document merge** — a positional post-processing pass that collapses any
  run of adjacent placeholders resolving to the same documentId into a single formatted citation with
  the sorted, de-duplicated union of pages. Different-document adjacency stays separate; non-adjacent
  reuse still substitutes normally; unknown ids fall through to the existing orphan-stripping.
  **Guarantees grouped citations regardless of model behaviour.** Covered by 12 new unit tests; the
  module suite (127 tests) is green.
- **Harness `stacks` metric** — counts same-document stacked runs in the *raw* model output, so
  prompt-level behaviour stays measurable now that post-processing hides it from users.
- **Document-ID iteration** — `HARNESS_DOCUMENT_IDS` accepts a comma-separated list; every query runs
  against every document, expanding the evaluation matrix across case files.
- **`ResponseQualityComparator`** — a quality-comparison stage that evaluates prompt variants
  chain-wise (each version vs its predecessor) on three layers: embedding cosine similarity of the
  citation-stripped prose (semantic equivalence), deterministic markdown structure counts, and a
  GPT-5.1 LLM judge scoring factual parity (EQUIVALENT / A_RICHER / B_RICHER / DIVERGENT, with
  material facts missing per side) and 1–5 adherence to each query's requested output format.

## 4. Latest evaluation run

**Configuration:** GPT-4o generation • prompts v2, v3, v4 • 10 production-shaped queries × 2 case
documents × 1 repetition = 60 generation calls • GPT-5.1 judge (reasoning_effort=none), 40 pairwise
judgements • **zero call failures**.

### 4.1 Citation metrics per prompt (raw model output, 20 rows each)

| Prompt | match (inline ⊆ JSON) | Prose words (total) | Cited pages (total) | Same-doc stacked runs |
|---|---|---|---|---|
| v2 baseline-with-improvements | 11/20 | 4,504 | 153 | 31 |
| v3 strict-citation-grouping | 11/20 | 4,305 | 122 | 21 |
| v4 compact | 11/20 | 4,602 | 137 | 22 |

> "match" misses are dominated by the four genuinely no-evidence queries (an empty JSON block counts
> as non-match by definition) and one document-specific witness-bundle pathology present under every
> prompt. Raw stacking is statistically flat across v3/v4 — see §5, point 2.

### 4.2 Quality comparison (chain-wise, GPT-5.1 judge, 20 rows per pair)

| Pair (A → B) | Mean cosine (prose embeddings) | Judge verdicts | Structure adherence (mean 1–5) |
|---|---|---|---|
| v2 → v3 | 0.9675 | EQUIVALENT 10 • B_RICHER 5 • A_RICHER 3 • DIVERGENT 2 | 4.2 → **4.5** |
| **v3 → v4 (the −53% compaction)** | **0.9746** | **EQUIVALENT 8 • B_RICHER 10 • A_RICHER 2 • DIVERGENT 0** | 4.2 → **4.6** |

> **Headline:** halving the prompt did not degrade the answers. The compaction step changed responses
> *less* than the previous prompt improvement did (higher mean cosine), the judge found the compact
> prompt's answers factually richer in 10 of 20 rows (equivalent in 8), and adherence to each query's
> requested output format *improved* (4.2 → 4.6). A plausible mechanism: with less rule-noise, the
> model attends more to the query instruction's actual format specification.

## 5. Findings

1. **v4 (compact) is the recommended production candidate.** −1,100 tokens on every call, factual
   parity at worst equal, structure adherence up, citation metrics level.
2. **Prompt wording alone cannot eliminate citation stacking on GPT-4o.** v3's stricter rules cut raw
   stacking in one earlier run (30 → 13) but showed no effect in another; across this run v3/v4 are
   flat (21/22). The deterministic `CitationProcessor` merge is what guarantees grouped citations — in
   the two-document validation run it merged 42 stacked runs (up to 9 adjacent markers into one
   citation) and left **zero** adjacent same-document citations in any formatted output.
3. **Enforcement belongs in code; intent belongs in the prompt.** The pipeline's post-processing
   (merge, drift normalisation, orphan-stripping) makes most defensive prompt text redundant — which
   is what made the 53% cut safe.
4. **Removing the trailing "no evidence" example caused no regression**: the genuinely unanswerable
   queries produced identical refusals under every prompt, and no substantive query flipped to a
   spurious "no information" answer.
5. **Caveats:** one repetition per cell (read aggregates, not single rows); one row ("Summary of
   recent convictions", second document) where v4 omitted several convictions v3 included — recommend
   re-running that query at 2–3 repetitions before adoption; cosine saturates high on same-topic
   text, so it is read comparatively (a red-flag detector), with the judge as the discriminating
   signal.

## 6. Recommendation and next steps

- Adopt `v4-strict-citation-grouping-compact` as the candidate for
  `RESPONSE_GENERATION_SYSTEM_PROMPT`, after a confirming run at 2–3 repetitions focused on the
  recent-convictions query.
- The `CitationProcessor` merge ships with the code and benefits production regardless of which
  prompt is deployed.
- Production configuration (local.settings.json / Azure app settings) must be updated separately —
  the checked-in prompt files configure only the harness.
- Raise the PR covering: v1–v4 prompt files, CitationProcessor merge + tests, harness stacking
  metric, document-ID iteration, and the quality comparator.

## 7. v5 domain-neutral prompt (28 October 2026)

Until per-client / per-use-case prompts can be externalised, the shared system prompt carries only
guardrails and the citation contract. v5 was evaluated against v4 on both model pairings, with each
model given its own query-prompt set (see `query-prompt-evaluation-prod-vs-test.md`).

**Judge position bias.** A control comparing v4 with an identical copy of itself gave the judge's
second answer (B) "richer" 12–26 times in 50 and the first (A) only 1–7 times. Judge verdicts are
therefore read against that A/A floor, not as raw counts; chain-wise "B richer" results in earlier
documents are inflated by this bias.

| Check | gpt-4o + production query prompts | gpt-5.1 + test query prompts |
|---|---|---|
| Judge vs A/A floor | not worse (v4 richer 1 vs floor 2) | not worse with v4 in the favoured B position (v4 richer 18 vs floor 20) |
| `FACT_MAP_JSON` present | 49/50 (v4 50/50); 12/12 on a 3-rep re-check | 50/50 (v4 50/50) |
| Uncited answers | 3/50 (v4 5/50) | 0/50 (v4 0/50) |
| Extraction (source-verified items) | equal or better on every spot check | 9/11 (v4 10/11) |

- Same-document marker spray (`[1]…[41]`) on one multi-witness document occurs under v4 as well; it
  is model behaviour, not caused by the prompt, and a prompt line targeting it had no effect.
- A trailing "no matching records" example (tried in an intermediate variant) cut gpt-5.1
  extraction recall sharply and was removed, consistent with §2's v3 finding.
- The integration suite and the eval smoke tier pass with v5 as the orchestration-test prompt.

**Known limitations:** gpt-4o still sometimes leaves summaries uncited (as under v4); gpt-5.1
extraction precision (near-match records, non-qualifying entries) to be revisited during the gpt-5.1
migration. v4 stays available as the config-only rollback value for `RESPONSE_GENERATION_SYSTEM_PROMPT`.

---

*Generated from the evaluation harness run of 18 June 2026 (60 GPT-4o generation calls, 40 GPT-5.1
judge calls, two case documents). Detailed methodology: `system-prompt-evaluation-cross-model.md`
and `system-prompt-evaluation-openai-sdk.md` in the harness module.*
