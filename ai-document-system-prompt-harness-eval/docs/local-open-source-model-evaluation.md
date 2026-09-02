# Local Open-Source Model Evaluation — Findings

> Can a locally hosted open-weight model be a credible candidate against the cloud baselines
> (gpt-5.1, Claude Sonnet 4.6) on the answer-retrieval citation contract? Four models were run
> through the same harness over **identical retrieval input** (an offline snapshot), compared
> against a **persisted gpt-4o / gpt-5.1 baseline**, with **no Azure access needed for the
> local runs**. Companion docs: `model-comparison-gpt51-claude-sonnet46.md` (the cloud bar),
> `claude-sonnet-azure-feasibility.md`.
>
> `cp-ai-rag-service` — harness module `ai-document-system-prompt-harness-eval`.

---

## 1. Method

Everything runs on the tooling delivered alongside this document:

- **Retrieval snapshot** (capture 6 Aug 2026): `user-queries-version-test.json` (prod + test) ×
  the 5 IDPC case documents — 12 distinct query texts, 60 retrievals, 257 chunks with vectors,
  kNN 80 / pool 60 / MMR-final 30, λ 0.8, containment dedup on. Every run below replays this
  snapshot (`HARNESS_RETRIEVAL_SNAPSHOT`), so all models — cloud and local — see **byte-identical
  chunks in identical order**.
- **Run configuration**: `user-queries-model-test.json` (10 tightened release-candidate queries ×
  5 documents = 50 cells), system prompt `v4-strict-citation-grouping-compact`,
  `LLM_MODEL_RESPONSE_MAX_TOKENS=7000`, `CITATION_GUARD_MODE=off`, 1 repetition.
- **Baseline**: one persisted cloud run (6 Aug 2026), gpt-4o (`gpt-4o-response-generation`) and
  gpt-5.1 in parallel streams over the snapshot — 100/100 cells, zero errors
  (`harness-results/harness-run-20260806-232338.json`).
- **Local serving**: LM Studio on an Apple M5 Max (128 GB): llama.cpp backend for GGUF, MLX for
  MLX builds; OpenAI-compatible server, Responses API; models loaded at 32,768 context (the
  largest cell carries ~16.3k tokens of chunks + ~1k system prompt + the 7k output budget).
- **Smoke gate** per candidate (before any full run): the "facts of the offences" query × 5
  documents. Pass bar: 5/5 parseable `<FACT_MAP_JSON>`, ≥4/5 inline⇄JSON match, viable latency.
- **Offline judge** (`BaselineJudgeTool`): pairwise verdicts between persisted runs; judge
  pluggable. With no Azure access in this phase, judging used a **local gpt-oss-120b judge** —
  provisional by design; re-judgeable later by gpt-5.1 over the same files with one env change.

## 2. Scoreboard

Reference bar (from the cloud comparisons): gpt-5.1 — 100% JSON block, 96% match, 0 uncited;
Claude Sonnet 4.6 — 100% / 98% / 0.

| Candidate (serving) | Scope | JSON block | Inline⇄JSON match | Notes |
|---|---|---|---|---|
| **gpt-oss-20b** (GGUF, 11.6 GB) | **full 50-cell run** | 36/48 (75%) | 28/48 (58%) | best local result; 2 cells lost to LM Studio engine 500s; ~44 s/call |
| deepseek-r1-distill-llama-70b (GGUF, 40 GB) | smoke | 3/5 | 1/5 | thinking model; 4.5–6.7 min/call; not progressed |
| gpt-oss-120b (MLX MXFP4-Q8, 63 GB) | smoke ×3 | 5/5 every run | 1/5 cold, 2/5 warm, 3/5 nudged | markers omitted wholesale; failing rows correlate with the same two documents |
| qwen3-30b-a3b-instruct-2507 (MLX 8-bit, 32 GB) | smoke ×2 | 5/5 | 1/5 base, 1/5 nudged | fastest (40–89 s); ignores length caps (460–704 words); marker/JSON id desync |

### gpt-oss-20b, the one full run, against the persisted baseline (identical inputs)

| Metric | gpt-4o | gpt-5.1 | gpt-oss-20b |
|---|---|---|---|
| Completed | 50/50 | 50/50 | 48/50 |
| Parseable FACT_MAP_JSON | 48/50 | 50/50 | 36/48 |
| Inline⇄JSON match | 33/50 (66%) | 48/50 (96%) | 28/48 (58%) |
| Mean prose words | 203 | 449 | 272 |
| Distinct citations / row | 2.0 | 6.7 | 2.3 |
| Same-document stacked runs | 1.1 | 4.1 | **0.2** |
| Stripped markers (total) | 200 | 37 | **14** |
| Uncited substantive answers | 12 | 0 | 10 |
| Mean latency | 3.9 s (cloud) | 13.9 s (cloud) | 44 s (local M5 Max) |

Split by query family: the 20B is **strong on narrative summaries** (facts, chronology: 5/5 JSON,
5/5 match) and **fails on the analytical queries** (applications / prosecution evidence /
convictions: match 1–3 of 5, all 10 uncited answers concentrated here). On the fixed-list
extraction queries it returns the correct bare `if_not_found` sentinel but omits the required
empty `<FACT_MAP_JSON>[]` block — off-contract in shape, benign in substance.

### Provisional judge verdicts (local gpt-oss-120b judge, 25 Aug 2026)

gpt-5.1 (A) vs gpt-oss-20b (B), 48 pairs from the persisted files
(`harness-results/judge-20260825-061205.json`): **A richer 16, B richer 3, equivalent 12,
divergent 16, unparsed 1**; mean structure 4.4 / 4.0. Direction matches the metrics (gpt-5.1
dominates 16:3 on decided rows). Caveat: the 33% DIVERGENT rate is anomalous — gpt-5.1-judged
comparisons never exceeded 8% — so either the 20B genuinely omits *different* facts than
gpt-5.1 (plausible given its much shorter answers) or the local judge applies the DIVERGENT
rubric loosely. **Re-judge with gpt-5.1 over the same files before quoting these numbers.**

## 3. The systemic finding

Six configurations across four models tell one consistent story:

1. **The JSON half of the contract is essentially solved.** Every local model emits a parseable,
   well-formed `<FACT_MAP_JSON>` block at 75–100%. Structure (headings, bullets) is broadly fine.
2. **The two-sided inline⇄JSON synchronisation is the systematic failure.** Models either write
   prose with no `[N]` markers at all (gpt-oss-120b: entire answers; qwen3: 0 markers against 15
   JSON entries) or let the id sets drift (qwen3: 27 markers against 9 entries). This is exactly
   the discipline the cloud models are strongest at (96–98% match), and it does not correlate
   with model size — the 20B beats the 120B on it.
3. **A prompt nudge helps presence, not synchronisation.** An experiment variant of the system
   prompt (explicit "both halves mandatory; marker-free prose is invalid; empty block on
   refusal") lifted the 120B from 2/5 to 3/5 and cut Qwen's fully-uncited answers from 3 to 1,
   but no configuration reached the ≥4/5 gate. (The variant is an uncommitted local experiment,
   deliberately not part of the delivered prompt set.)
4. **Outputs are not deterministic under LM Studio's parallel batching even at temperature 0** —
   identical inputs produced different match outcomes across repeated smokes (±1 row in 5).
   Treat any local smoke gate as carrying that sampling noise.

**Verdict: no locally served open-weight model tested is currently a contender** against the
≥90%-match bar. gpt-oss-20b is the best of them and a plausible floor (58% match, excellent
stacking discipline, modest verbosity), but the gap to gpt-5.1/Claude is structural, not
incremental.

## 4. Recommended next steps

1. **Contract-architecture experiment** (highest expected value): stop asking the model to keep
   two id sets in sync. Have it cite inline with self-describing markers and **derive** the
   FACT_MAP_JSON deterministically in post-processing. That removes the observed failure mode by
   construction; it is an eval-harness experiment first (candidate prompt + a small harness-side
   deriver), with production implications only if it wins.
2. **Re-judge the 20B run with gpt-5.1** when Azure access returns — one `judge-baseline.sh`
   invocation over the same persisted files — to calibrate the provisional verdicts (especially
   the DIVERGENT rate).
3. Further model families (Mistral Small, GLM-Air class) are one funnel pass each through the
   now-turnkey tooling, but rank below option 1: the bottleneck is the contract shape, not model
   choice.
4. If a local model ever passes, evaluate the **hosted** open-weight route (the same gpt-oss
   models are available serverless on Azure AI Foundry) — production would not run on a laptop,
   and hosting separates model quality from local serving quirks.

## 5. Local-serving operational notes (for whoever runs this next)

- **Context sizing**: load models with ≥32k context; the default 16k window overflows the
  largest cells (~25k tokens needed end-to-end).
- **Cold vs warm**: first calls after a big-model load can be pathological (21–26 minutes on the
  120B — shader compilation + paging); always send a priming call and measure warm.
- **LM Studio quirks encountered**: transient `peg-native format` 500s on gpt-oss outputs
  (absorbed as ERROR cells; a retry would clear them); the Responses API (`/v1/responses`) is
  supported and models JIT-load; the app build in use had a broken embedding worker, so the
  judge's optional local-cosine flag was unavailable; large downloads stall silently — resume
  loops around `lms get` handle it.
- **Latency ranking** (warm, this hardware): qwen3-30b-a3b ~40–89 s < gpt-oss-20b ~25–60 s
  (GGUF, small) ≈ gpt-oss-120b ~76–199 s (MLX) ≪ deepseek-70b ~4.5–6.7 min (dense GGUF).

---

*All runs replay retrieval snapshot `retrieval-snapshot-user-queries-version-test-20260806-*.json`
and persist to `harness-results/` (both git-ignored: verbatim case content). Baseline:
`harness-run-20260806-232338.json`; gpt-oss-20b full run: `harness-run-20260821-191725.json`;
provisional judge file: `judge-20260825-061205.json`.*
