# ai-document-system-prompt-harness-eval

Offline, on-demand evaluation harness for the answer-retrieval **system prompt** and **query
prompts**. It runs the real production answer-generation pipeline across a matrix of prompts, query
versions, models, documents and repetitions, then reports citation, verbosity, coverage and
response-quality metrics — including an LLM-judge comparison of variants.

This module is **not deployed**. It is a `main()` tool that makes real, billable Azure OpenAI calls;
it exists to decide prompt/model changes with evidence before they reach production.

---

## What it does

For every cell in the matrix

```
system prompts × models × (queries × query-versions × documents) × repetitions
```

it executes the exact production path used by the answer-retrieval function:

```
embed query  →  Azure AI Search (filtered by documentId)  →  ChunkFormatterUtility
             →  ResponseGenerationService  →  ChatService (gpt-4o / gpt-5.1)  →  CitationProcessor
```

Because it builds the chat service through the production `ChatServiceFactory`, the real
model-specific behaviour applies (reasoning models omit temperature/top_p and honour
`reasoning_effort`; gpt-4o gets temperature/top_p = 0).

It then produces two things:

1. **A consistency table** — one row per (query, prompt, model), aggregated over documents ×
   repetitions, with the metrics below.
2. **A quality comparison** — an LLM judge (default gpt-5.1) scores paired variants for factual
   parity and structure adherence (see *Comparison dimensions*).

### Metrics (per cell)

| Metric | Meaning |
|---|---|
| `ok` | runs that returned `ANSWER_GENERATED` |
| `json` | runs whose raw output contained a parseable `<FACT_MAP_JSON>` block (a shortfall on reasoning models signals reasoning-token truncation) |
| `match` | every inline `[N]` marker resolves to a JSON citation entry |
| `subst` | `CitationProcessor` rendered at least one `::(Source …)` |
| `proseAvg` / `wordAvg` | answer length excluding the JSON block and inline markers — a citation-independent verbosity measure |
| `cites` / `pages` | distinct citations and total source pages cited — coverage guards |
| `stacks` | same-document stacked `[N][M]` runs in the raw output (should be 0; the prompt asks the model to group them) |
| `rendered` / `stripped` | citations rendered to the reader vs. markers stripped as unresolved |
| `uncited` | substantive answers (≥ 50 prose words) with **zero** rendered citations |

### Comparison dimensions (LLM judge)

The quality stage compares variants **chain-wise** (each vs. its predecessor), pairing rows that are
identical on every dimension except the one being compared. It runs **whichever axes vary** — both
fire if both have ≥ 2 entries (independently; typically only one varies per run):

- **System prompts** — when `HARNESS_SYSTEM_PROMPTS` lists ≥ 2 prompts: prompt *i* vs *i-1*, per model.
- **Query versions** — when `user-queries.json` has ≥ 2 versions: version *i* vs *i-1*, per model.

Each answer is judged against **its own** query instruction (so a prompt that caps length is not
marked down for omitting detail it was told to omit). The judge returns a factual-parity verdict
(`EQUIVALENT | A_RICHER | B_RICHER | DIVERGENT`), the material facts missing from each side, and a
1–5 structure-adherence score.

> **Not yet supported:** a model-vs-model axis, and cross-cut comparisons (e.g. prompt-A-on-gpt-4o
> vs prompt-B-on-gpt-5.1). Generation and the metrics table cover every combination, but the judge
> only compares along the prompt or version axis. See *Known limitations*.

---

## How to run

Prerequisites: Java 21, Maven, `az login` (auth is `DefaultAzureCredential` — no API keys).

```bash
cp ai-document-system-prompt-harness-eval/.env.sample \
   ai-document-system-prompt-harness-eval/.env          # then fill in endpoints + knobs
az login
./ai-document-system-prompt-harness-eval/run-harness.sh
```

`run-harness.sh` sources `.env`, builds the module and its upstream dependencies, and runs the
harness. **Configuration is read solely from `.env`** — the script applies no defaults and no
overrides, so what the `.env` says is what runs. It echoes the effective settings at startup; check
them before the run burns calls.

---

## Configuration (`.env`)

All keys are read via `System.getenv` (the JVM cannot set its own env vars, hence the sourced file).
See `.env.sample` for the full template.

### The evaluation matrix

| Variable | Purpose |
|---|---|
| `HARNESS_SYSTEM_PROMPTS` | Comma-separated prompt file names (without `.txt`) under `src/main/resources/prompts`, in display order. **Env-controlled — no recompile to change which prompts run.** |
| `HARNESS_LLM_DEPLOYMENTS` | Comma-separated chat deployment names (e.g. `gpt-4o-response-generation,gpt-5.1`); all share `AZURE_OPENAI_ENDPOINT`. |
| `HARNESS_DOCUMENT_IDS` | Comma-separated document ids; every query runs against every id (rows suffixed with the id's first 8 chars when > 1). |
| `HARNESS_REPETITIONS` | Iterations per cell (temperature-0 still varies; ≥ 2 to judge consistency). |
| `HARNESS_MAX_QUERIES` | Optional cap on the number of base queries (fast smoke test). |

Query prompts live in `src/main/resources/user-queries.json` (see *Query file format*).

### Model / generation

| Variable | Purpose |
|---|---|
| `AZURE_OPENAI_ENDPOINT`, `LLM_CHAT_SERVICE_PROVIDER` | Chat endpoint + provider (`azure`). |
| `LLM_MODEL_RESPONSE_MAX_TOKENS` | Output-token budget (keep generous for gpt-5.1). |
| `LLM_REASONING_EFFORT` | Reasoning models only; `none` avoids reasoning-token truncation. |
| `CITATION_GUARD_MODE` | Set `off` for measurement runs so citation-degraded answers are measured, not thrown as `CitationDegradedException` (which the harness records as an ERROR cell). |
| `AZURE_EMBEDDING_SERVICE_ENDPOINT`, `AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME` | Embeddings (query vectors + the quality-comparison cosine). |

### Retrieval refinement

`AZURE_SEARCH_SERVICE_ENDPOINT`, `AZURE_SEARCH_SERVICE_INDEX_NAME`, and the sizing chain
`SEARCH_NEAREST_NEIGHBOURS_COUNT ≥ SEARCH_TOP_RESULTS_COUNT > SEARCH_MMR_FINAL_COUNT`, plus the
`SEARCH_RESULTS_ENABLE_CONTAINMENT_DEDUP` / `SEARCH_RESULTS_ENABLE_MMR` toggles. These behave exactly
as in the answer-retrieval function — see the root `CLAUDE.md` for the full description.

### Quality comparison

| Variable | Purpose |
|---|---|
| `HARNESS_JUDGE` | `true`/`false` — enable the LLM-judge stage. |
| `HARNESS_JUDGE_DEPLOYMENT` | Judge model (default `gpt-5.1`). |

### gpt-5.1 timeouts

gpt-5.1 reasoning calls can run for minutes with no bytes flowing. Set
`HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` and `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` generously
(e.g. 300 / 600) in `.env` — `run-harness.sh` no longer floors these for you.

---

## Query file format (`user-queries.json`)

Multiple query-prompt versions of the same queries, matched across versions by `queryId`:

```json
{
  "versions": [
    { "version": "prod", "queries": [ { "queryId": "…", "label": "…", "userQuery": "…", "queryPrompt": "…" } ] },
    { "version": "test", "queries": [ { "queryId": "…", "label": "…", "userQuery": "…", "queryPrompt": "…" } ] }
  ]
}
```

- `queryId` is the cross-version join key — a query is compared to the same-`queryId` query in the
  other version.
- Every query is expanded across all `HARNESS_DOCUMENT_IDS` and all versions.
- A single version is valid (just one `versions[]` entry) — the version axis then stays off and the
  prompt axis (if ≥ 2 prompts) drives the comparison instead.

---

## Common scenarios

| Goal | How |
|---|---|
| Compare two system prompts on one model | `HARNESS_SYSTEM_PROMPTS=promptA,promptB`, one model, one query version → prompt-axis judge comparison. |
| Compare two query-prompt versions across models | one prompt, two `versions[]`, `HARNESS_LLM_DEPLOYMENTS=gpt-4o…,gpt-5.1` → version-axis judge comparison, computed per model. |
| Screen one prompt across many models | list the models; you get the full **metrics table** for every model, but no model-vs-model **judge** verdict (see limitations). |

---

## Known limitations

- **Judge axes are prompt and query-version only** — there is no model-vs-model axis, and no
  cross-cut comparison (varying two dimensions at once, e.g. prompt-A/gpt-4o vs prompt-B/gpt-5.1).
  Generation and the metrics table still cover every combination.
- **Chain-wise comparison** — with 3+ prompts or versions the judge compares neighbours
  (v1↔v2, v2↔v3), not every pair against a baseline.

A generalisation (configurable comparison axis + baseline-vs-all + explicit cross-cut) is planned as
a follow-up.

---

## Related docs

- `docs/query-prompt-evaluation-prod-vs-test.md` — a worked prod-vs-test query-prompt evaluation.
- `docs/system-prompt-evaluation-cross-model.md`, `docs/system-prompt-evaluation-openai-sdk.md` —
  earlier system-prompt evaluations.
- Root `CLAUDE.md` — the retrieval pipeline and env vars the harness shares with the answer-retrieval
  function.
