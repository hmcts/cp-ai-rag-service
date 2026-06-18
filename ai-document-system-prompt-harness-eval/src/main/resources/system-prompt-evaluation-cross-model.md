# System prompt evaluation — cross-model (GPT-4o + GPT-5.1)

> **Purpose & status.** We are *considering* moving the answer-retrieval service from
> GPT-4o to the **GPT-5.1 reasoning model**. This is **not yet done in production** —
> production still runs GPT-4o. This exercise validates whether the system prompt **and the
> surrounding codebase** are suitable for that move: it re-opens the answer-retrieval system
> prompt, progressively enhances it to work across **GPT-4o** and **GPT-5.1**, and compares
> the deployed baseline prompt against an improved variant on both models using the harness in
> this module — focused on **citation generation** and **adherence to the citation/output
> rules**. Its conclusions are recommendations for *if/when* the migration proceeds.

## 1. Why this evaluation

An earlier GPT-4o-only evaluation concluded:

- The strongest prompt was the **production prompt plus a single negative example** —
  70% aggregate, 93% on findings queries.
- The single biggest reliability lever was a *decoding* setting, not the prompt:
  setting **`top_p=0`** alongside `temperature=0` lifted baseline match from 40% → 80%
  on a single-query probe. The all-caps `MUST`/`FORBIDDEN` wording creates clear
  single-token winners at decision points, which `top_p=0` then locks in.

**What the move would change:** the migration under consideration points the chat deployment
at **`gpt-5.1`** (`AZURE_OPENAI_CHAT_DEPLOYMENT_NAME = gpt-5.1`; set this way for evaluation,
not in production). GPT-5.1 is a *reasoning* model, and that breaks a load-bearing assumption
of the earlier evaluation:

> `ChatModelUtil.isReasoningModel("gpt-5.1") == true`, so `AzureChatService` sends
> **`max_completion_tokens` only** and **omits `temperature` and `top_p`** — reasoning
> models reject non-default sampling parameters.

So **the `top_p=0` lever that delivered the earlier evaluation's biggest gain would not
exist on the candidate model.** The prompt would have to carry the citation discipline
that GPT-4o gets from constrained decoding. That is the motivation for this work.

## 2. The starting point (deployed prompt)

The exercise starts from the **real deployed prompt** (`RESPONSE_GENERATION_SYSTEM_PROMPT`),
not the `sample` placeholder. It is the "British Legal Advisor" baseline: nine numbered
instructions covering inline `[N]` placeholders, one-placeholder-per-document, the
`<FACT_MAP_JSON>` block, the bracket guardrail, heading hierarchy, and British spelling.
Captured verbatim at `prompts/baseline-production.txt`.

### Known weaknesses carried by the baseline

| # | Weakness | Source of evidence |
|---|----------|--------------------|
| W1 | **Ambiguous multi-document rule.** Rule 3's title says "One Statement = One Citation ID per documentId" but rule 4 says aggregate *multiple documents* into the JSON under one placeholder. The different-document case is genuinely under-specified. | Earlier GPT-4o analysis |
| W2 | **No inline⇄JSON set-equality invariant.** Nothing forbids per-page fragmentation where the model emits `[1][2][3]` inline but only one JSON entry (or vice-versa). | Earlier "match" metric failures |
| W3 | **Redundant `pageNumbers` field.** The schema asks for both `pageNumbers` (compressed) and `individualPageNumbers`. `CitationProcessor.resolveCompressedPages` *derives* the compressed form from `individualPageNumbers` when `pageNumbers` is absent — so asking for both wastes output tokens and creates a class of disagreement bugs. | `CitationProcessor.java:184-190` |
| W4 | **No-findings branch invents citations.** On queries with no answer, the baseline scored 10% (GPT-4o) because nothing tells it to emit `<FACT_MAP_JSON>[]`. | Earlier no-findings results (baseline 2/20) |
| W5 | **Broken template reference.** Rule 1 references `{Retrieved Documents}` (literal curly braces) but the user message actually carries `<RETRIEVED_DOCUMENTS>` XML tags. | Inspection of `ChunkFormatterUtility` output |
| W6 | **Dead instruction.** "At the end of response, do not ask user for a follow up query" — reactive scaffolding for a problem that does not occur in one-shot RAG. | Inspection of the prompt |
| W7 | **Page-in-bracket drift not addressed.** GPT-4o's most common drift is `[1 p.3]` / `[1:3]`. `CitationProcessor` tolerates it, but it is noise the prompt never explicitly forbids. | Earlier GPT-4o analysis |

## 3. GPT-5.1-specific considerations

GPT-5.1 is not "GPT-4o but better" for prompt-adherence purposes. The differences
that matter for citations:

1. **No sampling control.** `temperature`/`top_p` are omitted (forced to default).
   The determinism GPT-4o leaned on is gone; rule *clarity and consistency* now
   does the work that `top_p=0` used to.
2. **Reasoning-token budget is the new truncation risk (severe).**
   `max_completion_tokens` is shared between the hidden reasoning trace **and** the
   visible answer + JSON block. If it is sized for GPT-4o, GPT-5.1 can exhaust the
   budget *mid-answer* and never emit `</FACT_MAP_JSON>`. Result:
   `CitationProcessor.findLastJsonTag` returns null → the answer renders with
   **naked, unresolved `[N]` markers** and no sources. This is a failure mode no prompt
   wording can fully prevent — it is a **config** problem (see §6) and a
   **CitationProcessor hardening** problem (§7).
3. **Reasoning models penalise contradictory instructions.** W1's ambiguity is more
   damaging on GPT-5.1, which will "reason about" the conflict rather than pick the
   GPT-4o high-probability token. Removing the contradiction helps 5.1 more than 4o.
4. **Less need for repetitive coercion; more need for a clean contract.** Stacked
   `CRITICAL`/`MUST`/`FORBIDDEN` was tuned to give `top_p=0` a sharp winner on 4o.
   On 5.1 it is largely inert and, in excess, invites meta-commentary. Emphasis is
   kept only where it earns its place.
5. **Verbosity / preamble.** Reasoning models like to preface. A preamble both wastes
   output-token budget (feeding risk #2) and can push the JSON block past the cut.
   An explicit "answer directly, no preamble" line mitigates both.

## 4. Design stance — incremental, not rewrite

The earlier GPT-4o evaluation is unambiguous: every from-scratch rewrite (a family of
XML-tagged prompts) collapsed to **24–26%**, while the production prompt and its
minimal-delta descendant scored **58–70%**. The XML rewrites silently dropped five
load-bearing rules (the bracket guardrail, the "ONE placeholder" emphasis, the page JSON
field, the heading hierarchy, the spelling reinforcement).

**Conclusion:** evolve the proven prompt; do not replace it. The improved prompt keeps
the full production rule structure and changes only what was proven safe on GPT-4o or
what GPT-5.1 specifically needs.

## 5. The improved prompt

Stored at `prompts/baseline-with-improvements.txt`. It is the deployed baseline **+**:

| Change | Addresses | Risk |
|--------|-----------|------|
| Multi-document rule split into explicit *same-document* (one `[N]`) vs *different-document* (one `[N]` per doc, adjacent) cases | W1 | Low — clarifies, not contradicts |
| **Inline⇄JSON set-equality** invariant | W2 | Low — the highest-value citation rule |
| Drop redundant `pageNumbers` JSON field; keep `individualPageNumbers` only | W3 | None — `CitationProcessor` derives the compressed form |
| Explicit empty-results branch `<FACT_MAP_JSON>[]` | W4 | Low |
| Fix `{Retrieved Documents}` → `<RETRIEVED_DOCUMENTS>` | W5 | None |
| Remove the dead "no follow-up" line | W6 | None |
| Explicit "placeholders are bare integers only" rule + page-in-bracket negative example | W7 | Low |
| Bold-markdown negative example | proven on GPT-4o | Low |
| **"Answer directly, no preamble, do not narrate reasoning"** | GPT-5.1 risks #2, #5 | Low |
| Calibrated emphasis (de-duplicated `MUST`/`FORBIDDEN`) | GPT-5.1 risk #4 | Low |

What the improved prompt deliberately **keeps** from the proven baseline: the bracket
guardrail, the heading hierarchy, British spelling, the `<FACT_MAP_JSON>` literal-tag
contract, and the worked RIGHT/WRONG examples. Nothing that was shown to work is removed.

## 6. Config recommendation (not prompt — but gates citation success on 5.1)

`LLM_MODEL_RESPONSE_MAX_TOKENS` (`AzureChatService` `maxTokens`, default `1000`)
is passed as `max_completion_tokens`. On `gpt-5.1` this budget must cover **reasoning
+ answer + the JSON block**. A value tuned for GPT-4o output length will truncate the
citation block on long legal answers.

- **Action:** if the move proceeds, ensure the value configured for the gpt-5.1 deployment is comfortably above the worst-case answer length
  (the prosecution-evidence summary runs ~100k characters of *input*; outputs are shorter
  but the longest findings answers plus reasoning overhead need headroom). Recommend ≥ 4000,
  and constrain reasoning via `LLM_REASONING_EFFORT` (see §11) since deep reasoning is not
  needed for extract-and-cite.
- This is the GPT-5.1 analogue of the earlier "`top_p=0` is itself a deployment-grade
  improvement" finding: **right-sizing `max_completion_tokens` (and reasoning effort) is the
  single biggest reliability lever on the reasoning model.**

## 7. CitationProcessor hardening

An existing guard against the catastrophic-bracket pathology (threshold 100) was already
implemented (`stripRunawayBracketsIfCatastrophic`). The hardening here targets the GPT-5.1
truncation mode (§3 risk #2).

**Implemented** (`CitationProcessor.stripUnresolvedCitationMarkers`, replacing the
catastrophic-only `stripRunawayBracketsIfCatastrophic`):

- **No-JSON-tag / truncated-tag path:** when `findLastJsonTag` returns null (no block,
  or an unclosed `<FACT_MAP_JSON>` from a reasoning-token cut-off), *all* unresolved
  bare `[N]` markers are now stripped — not just the >100 catastrophic case — after
  normalising joined `[1, 2]` forms. Previously a handful of orphaned brackets reached
  the user; now none do.
- **Post-substitution path:** any inline `[N]` left over after substitution (an inline
  id with no matching JSON entry — the partial-truncation / set-mismatch case) is
  stripped too.
- **Parse-failure path:** unresolved markers are stripped when the JSON block is present
  but unparseable.
- **Observability:** the three cases log distinct warnings (catastrophic counter-loop /
  likely-truncation / id-JSON mismatch) so cross-model drift is visible in logs.

Covered by `CitationProcessorGpt51Test` (5 tests); the existing 31 `CitationProcessor*`
tests still pass unchanged (no test relied on orphaned brackets surviving).

## 8. Methodology

The harness lives in this module, `ai-document-system-prompt-harness-eval`
(`src/main/java/uk/gov/moj/cp/harness/TestHarness.java`). It:

- **Runs both deployments.** The full prompt set runs against **both** `gpt-4o` and
  `gpt-5.1` so cross-model regressions are visible in one run, via the production
  `ChatServiceFactory` path (so the real `isReasoningModel` branch is exercised).
- **Tests two prompts.** `baseline-production` and `baseline-with-improvements`
  (`src/main/resources/prompts/*.txt`), over the query set in `user-queries.json`.
- **Metric `jsonBlockPresent`** — did the response contain a parseable
  `<FACT_MAP_JSON>…</FACT_MAP_JSON>` at all? This isolates the GPT-5.1 reasoning-token
  truncation mode from ordinary citation-matching failures.
- **Other metrics:** `ok` (answer generated), `match` (inline ids ⊆ JSON ids), `subst`
  (≥1 `::(Source …)` after `CitationProcessor`), plus prose length (verbosity) and
  cited-page count (coverage), classified by expected outcome (findings vs no-findings).

> **Caveat:** the harness cannot distinguish "higher recall" from "more hallucination" on
> no-findings queries. 2–3 responses per model still need manual review against the IDPC
> before any prompt is promoted.

## 9. Running the harness

```bash
cd ai-document-system-prompt-harness-eval
cp .env.sample .env                        # then fill in endpoints/deployments
az login                                   # services authenticate via DefaultAzureCredential
./run-harness.sh                           # sources .env, builds the module, runs TestHarness via exec:java
```

The script sources `.env` (exporting every key, since the production services read config
via `System.getenv`), applies harness-knob defaults, floors the HTTP timeouts for gpt-5.1,
then runs the harness. Knobs (set in `.env` or export): `HARNESS_REPETITIONS`,
`HARNESS_LLM_DEPLOYMENTS` (e.g. `gpt-5.1` for a single model), `HARNESS_MAX_QUERIES=1` for a
fast smoke test, `LLM_REASONING_EFFORT`. **Do not run before reviewing the prompts** — a full
10-query × 2-rep run across both models is ~160 billable calls.

## 10. Initial smoke run, and the revision it drove (2 queries × 3 reps × gpt-4o + gpt-5.1)

A smoke run (baseline vs the *first draft* of the improved prompt, `max_completion_tokens=7000`)
was **not** a win for the improved prompt and exposed two failure modes that drove a revision:

| Metric | baseline | improved (first draft) |
|---|---|---|
| json block present | 11/12 | 12/12 |
| match (inline ⊆ JSON ids) | **11/12** | 7/12 |
| subst (≥1 citation rendered) | 11/12 | 11/12 |

- **Truncation confirmed + hardening validated.** `baseline / Chronology / gpt-5.1`
  ran 336 s, emitted 66 inline markers, and was cut off before `</FACT_MAP_JSON>`
  (no block). The new `stripUnresolvedCitationMarkers` removed all 66 orphans, so
  the user got clean prose. The improved prompt never truncated (json 12/12) — the
  "answer directly, no preamble" line plausibly bought the headroom.
- **Failure 1 — bracket spray (gpt-4o).** The draft's "one `[N]` = one document / never
  `[1][2]` for the same doc" plus the adjacent-`[1][2]` example made the model emit
  `[1][2]…[29]` (one bracket per *chunk*) for a single document, with one aggregated
  JSON entry. (The hardening salvaged the render, but the behaviour is wrong.)
- **Failure 2 — id derived from GUID (gpt-5.1).** The model put each `<DATA CHUNK_ID>`
  GUID into the `documentId` field and used its hex prefix as the `citationId`
  (`1730`, and invalid `ae2d`/`f2`), making the whole JSON unparseable → all citations
  lost.

**Root cause:** the draft's *document-centric* framing of `citationId`. The proven baseline
and the downstream `CitationProcessor` treat `citationId` as a **per-statement sequential
counter** (a document cited for three facts → `[1] [2] [3]`). The "one `[N]` per document /
collapse same-document" reframing caused both the spray and the id-from-GUID behaviour.

**The revision** (current `prompts/baseline-with-improvements.txt`):
1. `citationId` is a **plain sequential counter**, explicitly **never** derived from
   DOCUMENT_ID, CHUNK_ID, page number, or any GUID/hex.
2. New "How the retrieved documents are structured" section explaining
   `<DOCUMENT DOCUMENT_ID>` vs `<DATA CHUNK_ID>`, with "never use a CHUNK_ID".
3. Citation rule restored to the baseline's per-statement model: same-document/several-pages
   → one `[N]`; different-documents/one-statement → adjacent `[N]`s; **same document
   across different statements → multiple sequential `[N]`s (expected, don't merge)**.
4. Schema: `documentId` = the enclosing `<DOCUMENT>`'s DOCUMENT_ID, not a CHUNK_ID.
5. Two new WRONG examples pinning the spray and the GUID-as-citationId modes.

## 11. Final outcome — length/reliability run (10 queries × 2 reps, both models)

Ran gpt-4o once (reference) and gpt-5.1 three times (`reasoning_effort` ∈ none/minimal/low),
baseline + improved, 5s inter-call delay, `max_completion_tokens=7000`.

**`reasoning_effort=none` is the recommended setting for the gpt-5.1 deployment.** The empty-`finish_reason=length`
failures track effort monotonically — proving the failure is reasoning-token-budget
exhaustion, not TPM throttling (the 5s delay didn't help):

| effort | empty/length failures | gpt-5.1 cells with ok < 2/2 |
|--------|----------------------|------------------------------|
| **none** | **0** | none — all ok 2/2 |
| minimal | 6 | Chronology, Witnesses |
| low | 10 | Chronology (both prompts), Witnesses |

`none` puts the whole budget into output → zero truncations across all 20 gpt-5.1 cells,
citations clean (no GUID/spray pathology on any of the 10 queries), and it is also the
*least* verbose of the three on the big narrative queries.

**Strict length parity was dropped — deliberately.** Effort is a reliability lever, not a
length lever: the verbosity ratio (gpt-5.1 ÷ gpt-4o) is roughly flat across efforts and
never approaches 1.3×. Mean ratio over the 5 substantive findings queries: none ~5.0×,
minimal ~5.3×, low ~5.7×. The coverage guard explains it — gpt-5.1 cites **5–22× more
sources/pages** than gpt-4o, so its extra length is largely *real citation coverage, not
padding*. Forcing parity would require a hard cap that deletes those citations. Decision:
accept gpt-5.1 as the fuller, better-cited model; keep the (structure-deferring)
conciseness directive to trim genuine padding.

**Chat-provider assumption (confirmed):** the service is configured with
`LLM_CHAT_SERVICE_PROVIDER=azure` → `AzureChatService` (true on GPT-4o today, and the path a
gpt-5.1 deployment would use). It is the implementation this eval exercised and the one that
honours `LLM_REASONING_EFFORT`. (The alternative, `OpenAiChatService` (`provider=openai`, OpenAI
Responses API), ignores `reasoning_effort` and instead exposes `verbosity` via
`LLM_MODEL_RESPONSE_VERBOSITY` (default `low`); if the service ever switches to that provider,
the length/reliability lever changes and this eval should be re-run on that path — the harness
supports it via `LLM_CHAT_SERVICE_PROVIDER` in `.env`.)

**Recommended configuration for the move** (nothing here is live in production yet; the
codebase changes are merged, the deployment settings are to be applied *if/when* the
migration proceeds):
- Prompt: `prompts/baseline-with-improvements.txt` (→ `RESPONSE_GENERATION_SYSTEM_PROMPT`) — *deployment setting to apply with the move.*
- `LLM_REASONING_EFFORT=none` for the gpt-5.1 deployment (already defaulted in `AzureChatService`;
  only applied to reasoning models — gpt-4o is unaffected). *Codebase change merged.*
- `CHUNK_ID` removed from `ChunkFormatterUtility` output (closes the GUID-citationId surface). *Codebase change merged.*

**Gate before adopting gpt-5.1 in production:** `reasoning_effort=none` means gpt-5.1 does no
hidden reasoning. Coverage held in the metrics, but extraction *accuracy* is not machine-
measurable here — spot-check 2–3 gpt-5.1 answers (esp. Chronology/Prosecution) against the
IDPC before the migration is approved.

## Appendix: prompt inventory

| File | Role |
|------|------|
| `prompts/baseline-production.txt` | Deployed prompt — the starting point / control |
| `prompts/baseline-with-improvements.txt` | **The candidate** — baseline + the citation and cross-model improvements (§5) |
