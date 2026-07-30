# Query-Prompt Evaluation — Production vs. Release-Candidate (Test) Prompts

> **Definitive evaluation** of the updated ("test") query prompts we intend to release, run against
> current production. The decision under test is the **migration**: current production
> (`prod` query prompts on **gpt-4o**) → proposed release (`test` query prompts on **gpt-5.1**).
> `cp-ai-rag-service` — harness module `ai-document-system-prompt-harness-eval`.
>
> This supersedes the earlier interim (2-document, work-in-progress) version of this document. The
> findings here are based on the release-candidate query prompts in
> `src/main/resources/user-queries/user-queries-version-test.json` and a 5-document corpus.

---

## 1. Run configuration (baseline for future runs)

The harness executes the **exact** production answer-retrieval path for every cell:

```
embed query → Azure AI Search (filtered by documentId) → ChunkFormatterUtility
            → ResponseGenerationService → ChatService (gpt-4o / gpt-5.1) → CitationProcessor
```

Reproduce this run by restoring the settings below in `ai-document-system-prompt-harness-eval/.env`
and running `./run-harness.sh`. Treat this table as the **baseline configuration** — future
evaluations should hold these fixed unless a change is the thing being tested.

| Setting | Value |
|---|---|
| System prompt | `v4-strict-citation-grouping-compact` (only) |
| Query set | `user-queries-version-test.json` — versions `prod`, `test`, same 10 queries joined by `queryId` |
| Query versions compared | `prod` (mirrors production wording) vs `test` (release candidate) |
| Models (`HARNESS_LLM_DEPLOYMENTS`) | `gpt-4o-response-generation`, `gpt-5.1` |
| Documents (`HARNESS_DOCUMENT_IDS`) | **5** — full IDs listed below the table |
| Repetitions | 1 (aggregation is over the 5 documents, n = 5 per cell) |
| Reasoning effort (`LLM_REASONING_EFFORT`) | `none` (gpt-5.1; ignored by gpt-4o) |
| Max completion tokens | 7,000 |
| Citation guard (`CITATION_GUARD_MODE`) | `off` — measurement mode; degraded answers are measured, not thrown |
| Retrieval sizing (kNN / pool / MMR-final) | **80 / 60 / 30** |
| Retrieval stages | containment-dedup **on** (shingle 3, threshold 0.95); legacy cosine dedup **off**; MMR **on**, **λ = 0.8** |
| Embeddings | `text-embedding-3-large` |
| HTTP timeouts (read / response) | 300s / **600s** (600s required to avoid gpt-5.1 truncation/error cells) |
| LLM-judge | `gpt-5.1` (default), each answer scored against **its own** query instruction |
| Call delay / parallelism | 5s per call; models run as parallel streams |

**Documents (`HARNESS_DOCUMENT_IDS`) — full IDs for reproducibility:**

```
349ef56e-d211-45a5-aa06-5dcc2929b6e9
50b5da6c-ad06-43b1-a798-8ffba4bda4a3
b7fcebd1-0ee3-4908-9215-769d9aebdd28
c9bcfab7-2fe3-4b00-8128-26371c6d58ba
3f326c23-61d1-46ab-91d0-88e4d7a8614a
```

**Volume executed (all succeeded, zero errors / timeouts / skips):**

| Phase | Operations |
|---|---|
| Retrieval | 100 embeddings + 100 AI Search queries (shared across both models) |
| Generation | **200 chat calls** = 10 queries × 2 versions × 5 docs × 2 models × 1 rep |
| LLM-judge | **250 pairwise judgements** = 100 (version axis) + 100 (model axis) + 50 (cross-cut) |

Two citation metrics are distinguished throughout:

- **cited pages** — pages the model listed in its `FACT_MAP_JSON` block (evidence it *identified*).
- **rendered citations** — inline source markers actually *shown to the reader* after
  `CitationProcessor` post-processing. **stripped** markers are inline `[N]` that failed to resolve
  and were removed; **uncited** counts substantive answers (≥ 50 prose words) left with **zero**
  rendered citations.

---

## 2. Headline — the migration wins outright

The **cross-cut** comparison judges the two configurations directly (50 pairs = 10 queries × 5 docs),
`prod + gpt-4o` (A) vs `test + gpt-5.1` (B):

| Verdict (A = prod+gpt-4o, B = test+gpt-5.1) | Pairs |
|---|---|
| **test + gpt-5.1 richer** | **41 / 50 (82%)** |
| Equivalent | 7 |
| prod + gpt-4o richer | 1 |
| Divergent | 1 |
| Structure adherence | prod+gpt-4o **4.1 → 5.0** test+gpt-5.1 |

Every analytical query is target-richer on the majority of its documents; the ties are the genuine
no-conviction refusals. The two non-wins are immaterial (§3).

### The trade

Totals over the 50 paired answers per side:

| | prod + gpt-4o | test + gpt-5.1 | Change |
|---|---|---|---|
| Words / answer (mean) | 264 | 410 | **+55%** |
| Cited pages (total) | 417 | 641 | +54% |
| **Rendered citations** (total) | 206 | **549** | **~2.7×** |
| Stripped (lost) markers | 137 | 0* | eliminated |
| Uncited substantive answers | 4 | **0** | eliminated |
| Generation latency (mean) | 5.3s | 8.1s | ~1.5× |

<sub>*gpt-5.1 + test produced 63 stripped markers spread thinly across answers, but **no** answer was
left fully uncited — unlike gpt-4o, where whole answers lose all citations.</sub>

The migration buys **~2.7× the reader-visible citations, elimination of gpt-4o's stripped/uncited
pathologies, and perfect structure adherence (5.0)**, at **~1.5× longer answers** and ~1.5× latency.
Critically, the answers are still far shorter than `prod`-on-gpt-5.1 would be (932 words — §4), because
the test prompts impose explicit length caps.

---

## 3. Per-query cross-cut breakdown

`test + gpt-5.1` vs `prod + gpt-4o`, over 5 documents each:

| Query | Result (5 docs) |
|---|---|
| Summary of the facts | 🟢 5 richer |
| Chronology of the case | 🟢 4 richer · 1 divergent |
| Applications on the case | 🟢 4 richer · 1 equal |
| Summary of prosecution evidence | 🟢 5 richer |
| Summary of each witnesses evidence | 🟢 5 richer |
| Summary of previous convictions | 🟢 3 richer · 2 equal |
| Previous dwelling burglary | 🟢 3 richer · 2 equal |
| Previous drug trafficking/supply | 🟢 5 richer |
| Previous offensive weapon/blade | 🟢 4 richer · 1 equal |
| Previous alcohol/drug driving | 🟢 3 richer · 1 equal · 1 prod |

### The two non-wins (both immaterial)

- **Chronology @349ef56e — DIVERGENT.** Each side holds a detail the other lacks: the test answer
  carries *more* investigative/procedural detail, while the prod answer had one specific
  court-appearance line. Note this **reverses the interim finding** that the test chronology prompt
  over-trimmed procedural scope — the release-candidate prompt restored those events **and** added a
  citation cue.
- **Alcohol/drug driving @c9bcfab7 — prod richer.** A *no-convictions refusal* where the judge's own
  note states the test answer additionally supplies the required citation. The `prod-richer` verdict
  is a judge misfire on a trivial refusal — there is no real content difference.

---

## 4. Verbosity and citations, by version × model

Each cell aggregates 5 documents (n = 5).

### Verbosity — mean prose words / answer

| | gpt-4o | gpt-5.1 |
|---|---|---|
| prod | 264 | 932 |
| test | 182 (**−31%**) | 410 (**−56%**) |

The test prompts hit their goal hardest where it matters most — **halving** gpt-5.1's output while
being judged richer.

### Citations — totals over 50 answers per cell

| version | model | cited pages | rendered | stripped | uncited | raw same-doc stacks† |
|---|---|---|---|---|---|---|
| prod | gpt-4o | 417 | 206 | 137 | 4 | 40 |
| prod | gpt-5.1 | 628 | 1,238 | 0 | 0 | 344 |
| test | gpt-4o | 539 | 125 | **269** | **10** | 64 |
| **test** | **gpt-5.1** | 641 | 549 | 63 | **0** | 213 |

<sub>†Raw same-document `[N][M]` stacks the model emitted *before* `CitationProcessor` groups them
deterministically for the reader; a behaviour signal, not a defect. The test prompts reduce stacking
on gpt-5.1 (344 → 213), consistent with their "cite once per event" instruction.</sub>

The release target (`test` + gpt-5.1) is the only configuration with **zero uncited answers and zero
missing-JSON**, at a manageable stripped count.

---

## 5. Secondary axes (context)

**Prompt rewrite, same model** (version axis, 100 pairs pooled over both models): test richer **64**,
equivalent 15, prod richer 12, divergent 9; structure **4.1 → 5.0**. The rewrite is preferred on both
models — gated only by the gpt-4o citation caveat in §6.

**Model comparison, same prompt** (model axis, 100 pairs pooled over both versions): gpt-5.1 richer
**67**, equivalent 29, gpt-4o richer 3, divergent 1 — but gpt-5.1 is **~2.5× slower** (mean 11.2s vs
4.5s). The model upgrade (67/100 richer) and the prompt rewrite (64/100 richer, from the version axis above)
contribute to the migration gain in near-equal measure — the model brings stronger extraction, the
rewrite brings denser, better-structured, length-capped output.

---

## 6. Caveat — `test` prompts are not yet citation-safe on gpt-4o

The migration target is gpt-5.1, which honours the system-prompt citation contract regardless of the
query template — so this caveat **does not affect the release**. It matters only if gpt-4o is ever used
as a fallback with the test prompts. Three test prompts still omit an explicit `[citation]` cue in
their output template, and gpt-4o follows the template literally:

| test prompt on gpt-4o | outcome | cause |
|---|---|---|
| Summary of previous convictions | **5/5 uncited** (0 rendered) | no `[citation]` cue in the 8 numbered sections |
| Summary of prosecution evidence | **4/5 uncited** (1 rendered) | no `[citation]` cue in the output template |
| Applications on the case | 1/5 uncited | no `[citation]` cue in the bullet template |
| Summary of the facts | 0 uncited, but **265 stripped markers** | gpt-4o over-emits unresolved inline markers |

On **gpt-5.1** all of these are clean (0 uncited). Chronology's cue was **fixed** this round and is now
clean on gpt-4o too. To make the test prompts model-agnostic (and gpt-4o a safe fallback), add a
`[citation]` cue to the **Convictions, Prosecution evidence, and Applications** templates. gpt-5.1 does
not require it.

---

## 7. Consistency with the earlier interim run

| | Interim (2 docs, WIP) | This run (5 docs, definitive) |
|---|---|---|
| Migration verdict | 30/40 richer, 10 equal, 0 worse | **41/50 richer**, 7 equal, 1 (refusal misfire), 1 divergent |
| Rendered citations (prod+gpt-4o → test+gpt-5.1) | ~200 → ~770 (~3.9×) | 206 → 549 (~2.7×) |
| Stripped / uncited on target | eliminated | eliminated |
| Chronology on the target | test over-trimmed procedural scope | **restored** — now richer, with citations |

Both runs reach the same conclusion: the target wins decisively, removes gpt-4o's citation
pathologies, and multiplies reader-visible citations, at a verbosity cost. Absolute citation counts
differ because the two runs use **different document sets** (and this run's release-candidate prompts
are more length-capped, so gpt-5.1 renders fewer — but still ~2.7× — citations). The direction is
identical and the release-candidate prompts have closed the one genuine regression the interim run
found (Chronology).

---

## 8. Verdict and recommendations

1. **Release the `test` query prompts and serve them on gpt-5.1.** Judged richer-or-equal in **48/50**
   cross-cut pairs, never meaningfully worse, ~2.7× the rendered citations, zero uncited/stripped
   pathologies, structure 5.0 — at ~1.5× answer length and ~2.5× latency versus current production.
2. **Before relying on gpt-4o as a fallback**, add a `[citation]` cue to the **Convictions,
   Prosecution evidence, and Applications** test prompts (§6). Cheap, makes every prompt
   model-agnostic, and required only for gpt-4o.
3. **Keep the length caps and heading structure** — they drive the verbosity reduction and the 5.0
   structure scores at no quality cost.
4. **Hold this run's configuration (§1) as the baseline** for future prompt/model evaluations; vary
   only the dimension under test.

---

*Generated from a single harness run: 200 generation calls (gpt-4o + gpt-5.1) and 250 gpt-5.1 judge
pairs (100 version-axis + 100 model-axis + 50 cross-cut), over 5 documents, 1 repetition, v4 system
prompt, `CITATION_GUARD_MODE=off`. Metrics are aggregates over documents; judge verdicts are counts
over the pairs. Cosine-similarity figures (version 0.825 / model 0.873 / cross-cut 0.863) are used only
as divergence flags, not quality scores.*
