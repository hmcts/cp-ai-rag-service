# AI RAG Service — Model Migration Cost Projection (gpt-5.1 vs Claude Sonnet 4.6)

**Status:** Draft for review.

**Evaluation summary:** A controlled cross-model evaluation of the two migration candidates — gpt-5.1
(Azure OpenAI) and Claude Sonnet 4.6 (Anthropic, hosted on Azure AI Foundry) — comprising 100 requests per
model across 50 query×document cells, two iterations each, with every request pair generated from
identical retrieved context. Per-request token usage was taken from the providers' API usage metadata
(never estimated) and priced at current published list rates. Answer quality was assessed on the same runs
by an LLM judge with manual verification of the material findings. Volumetrics for the national projection
come from the production pilot, as documented in the pilot usage and national rollout projections (confluence page), 
with one deliberate uplift described in §2.

## 1. Purpose

The AI RAG Service currently runs on Azure OpenAI GPT-4o. The intent is to **migrate to a newer,
current-generation model**, and this exercise is part of that migration assessment. Two candidates are
compared: **gpt-5.1** (Azure OpenAI) and **Claude Sonnet 4.6** (Anthropic). A companion evaluation
(`model-comparison-gpt51-claude-sonnet46.md`) compares the candidates on answer quality and citation
behaviour; this document adds the cost dimension — measured per-request economics projected to national
rollout volumes. Together they support the argument for either model and pave the way for adoption of
whichever is selected.

Pilot data is used here only as the **volumetric basis** for projection: document volumes, throughput
profile, and per-call token shapes. One volumetric is deliberately uplifted from the pilot observation:
the pilot averaged ~7 queries per document, but the target use case indicates **~10 queries per
document**, and the projections below reflect that.

## 2. Volumetric basis

| Volumetric | Value |
|---|---|
| Documents ingested | 2,000/day — national-rollout volume per the infrastructure cost model (the pilot itself averaged ~27/day) |
| Queries per document | **10** (use-case basis; pilot observed ~7.3 — 15,200 queries over 2,086 documents) |
| Response-generation queries | **20,000/day ≈ 7.3M/year** (2,000 × 10) |
| Groundedness-scoring calls | **20,000/day ≈ 7.3M/year** — assumes every answered query is scored (pilot scored ~96%) |
| Throughput | ~14 QPM as a 24-hour average; ~17 QPM sustained / ~44 QPM peak (the cost model's national profile of 12/31 QPM scaled 10/7 — the pilot itself peaked under 1 QPM); traffic concentrated in overnight, async-tolerant batch windows |
| Scoring token shape | ~5,200 input + ~100 output per call (pilot-observed) |
| Fixed platform costs (ingestion £197K + vector DB £44K + function apps £73K — provider-independent) | £314K/year¹ |
| Billed-vs-list uplift observed on production billing | ~×1.2 (billed £0.036/query vs £0.029 computed at list on pilot tokens — retries, FX, billing overheads) |

> ¹ Document volume is unchanged, so ingestion and vector-DB sizing hold; the higher query rate may pull forward function-app scale-out, which would raise the fixed line equally under either model path.

## 3. Measured per-request economics (the two candidates)

Token counts are taken verbatim from each API's usage block (never estimated). Prices are current
published list rates: gpt-5.1 = $1.25/$10 per MTok (GlobalStandard SKU confirmed on the deployment);
Claude Sonnet 4.6 = $3/$15 (Foundry CCU billing converts tokens at Anthropic list rates). No
reasoning/thinking tokens, no caching active. FX: £1 = $1.27 (as used in the infrastructure cost model).

> **Hosting note.** The Claude Sonnet 4.6 evaluation was carried out on **Azure** (Microsoft Foundry,
> native Anthropic Messages API, Global Standard routing). Foundry offers **no UK data residency** for
> Claude — UK-resident processing is currently available only on **AWS Bedrock** (`eu-west-2` in-region).
> Token counts and Anthropic list rates are identical across the two platforms, so the measurements
> transfer; a Bedrock in-region deployment would add AWS's ~10% regional-endpoint premium over global
> routing and uses the AWS Converse/InvokeModel API shape rather than the Messages API used in this
> evaluation (see §6, levers 1 and 5).

| | gpt-5.1 | Claude Sonnet 4.6 |
|---|---|---|
| Avg input tokens / request | 13,052 | 14,639 |
| Avg output tokens / request | 1,019 | 702 |
| Avg cost / request (USD) | $0.0265 | $0.0545 |
| Avg cost / request (GBP) | £0.0209 | £0.0429 |

**Claude Sonnet 4.6 costs 2.05× gpt-5.1 per request on this workload.** Decomposition: the input side runs
at 2.69× (2.4× price × 1.12× tokenizer density on identical chunks); the output side is at effective
parity (1.5× price × 0.69× volume — Claude writes ~31% fewer output tokens). The workload is ~93% input
tokens, so the blended multiplier sits near the input ratio.

The measured token shapes are consistent with the pilot's per-call averages (gpt-5.1 input ~13% above the
pilot shape, explained by the evaluation retrieval configuration and prompt version; a further ~12% on
Claude is tokenizer density), so the projections in §4 rest on measured data rather than assumption.

## 4. National rollout projection

### Response generation (7.3M queries/year)

| Backend | £/query (list) | Annual (list) | Annual (×1.2 billed uplift) |
|---|---|---|---|
| **gpt-5.1 (measured)** | £0.0209 | **£153K** | £183K |
| **Claude Sonnet 4.6 (measured)** | £0.0429 | **£313K** | £376K |
| Claude Sonnet 5 (estimated¹) | ~£0.037 | ~£270K | ~£324K |
| Claude Sonnet 4.6 + **Batch API** (estimated²) | £0.0214 | £156K | £187K |

> ¹ Sonnet 5's published list price is $2/$10 per million input/output tokens (vs $3/$15 for Sonnet 4.6), but its newer tokenizer produces ~30% more tokens for the same text, so the net per-request saving is ~13%, not 33%. Needs a measured re-run to confirm.

> ² Estimated: the Batch API's flat 50% discount on input and output applied to the measured Sonnet 4.6 per-request cost — not itself measured, since batches were unavailable on the evaluation platform. **Availability constraint: batches are offered on the Claude API (first-party) and Claude Platform on AWS, not on Microsoft Foundry or Amazon Bedrock** — see §6.

### Groundedness scoring (7.3M calls/year, pilot token shape)

Scoring must also move off GPT-4o as part of the migration. Candidate options, estimated from the pilot
scoring token shape:

| Backend | £/call (list) | Annual (list) |
|---|---|---|
| gpt-5.1 (estimated³) | ~£0.0059 | ~£43K |
| **Claude Haiku 4.5** ($1/$5, estimated³) | ~£0.0047 | **~£34K** |
| Haiku 4.5 + Batch API (−50%, estimated³) | ~£0.0024 | ~£18K |

> ³ Pilot token shape at list rates; Claude figures include ~12% tokenizer uplift. Scoring quality on the candidate models should be validated in the same evaluation framework before adoption.

### Indicative all-in migration scenarios (annual, list rates + £314K fixed)

| Scenario | Chat-LLM | Total |
|---|---|---|
| **gpt-5.1 path** — gpt-5.1 answers + gpt-5.1 scoring | ~£196K | **~£510K** |
| **Claude path A** — Sonnet 4.6 answers + Haiku 4.5 scoring (Foundry, PAYG) | ~£347K | **~£661K** |
| **Claude path B** — Sonnet 4.6 + Haiku 4.5, both via Batch API (estimated) | ~£174K | **~£488K** |

At ×1.2 billed uplift the totals become ~£549K, ~£730K and ~£523K respectively.

The pilot's throughput profile makes path B unusually credible: queries are heavily concentrated in
overnight batch windows and the async path already tolerates deferred completion — the canonical Batch API
workload shape.

## 5. Quality context (the other half of the argument)

Cost is one axis of the migration decision; the companion evaluation (100 paired responses, LLM-judged and
manually verified) supplies the other:

- Claude Sonnet 4.6 found qualifying prior convictions that gpt-5.1 denied existed in 4 of the 50
  query×document cells evaluated — including one **stochastic** case (gpt-5.1 found the conviction in one
  iteration and denied it in the next, on identical input). False "no record" answers are the
  highest-severity failure for this product.
- In every miss, the evidence was demonstrably in gpt-5.1's context (it cited the very pages containing
  the convictions). These are generation failures, not retrieval failures.
- Elsewhere quality is comparable: of the 100 judged pairs, 35 were equivalent, gpt-5.1 was richer on 21
  (mostly broad narrative summaries), Claude was richer on 34 (mostly targeted record screens), and 10
  were divergent; instruction adherence was tied (4.8 vs 4.7 out of 5).

Read together: the gpt-5.1 path is ~£151K/year cheaper at list (£510K vs £661K), but carries a measured
recall risk on conviction screening; the Claude path buys that recall back at a premium that batching
(path B, ~£488K) can more than neutralise where platform availability allows.

## 6. Structural cost levers and open questions

1. **Batch API availability.** The single biggest structural lever on the Claude path (−50%) is currently
   unavailable on the two platforms that best fit the residency posture (Foundry, Bedrock). Open question:
   Foundry roadmap for batches, or the residency guarantees available on Claude Platform on AWS / the
   first-party API for a UK government workload. (Bedrock eu-west-2 in-region serves Sonnet 4.6 today,
   also without batches.)
2. **Prompt caching.** Requests are ~93% input tokens; the system prompt (~1.5–2K tokens) repeats
   verbatim, the use case implies ~10 queries per document, and traffic clusters in overnight windows —
   per-document chunk context may be re-usable across queries within a cache TTL, and the higher
   queries-per-document ratio improves the achievable hit rate. With Claude cache reads at 0.1×
   ($0.30/MTok) and writes at 1.25–2×, request-shaping (breakpoint placement, 5m vs 1h TTL, stable
   chunk ordering per document) determines the hit rate. Even a 50% input-cache hit rate would cut
   Sonnet 4.6 response-generation cost by ~36% (~£113K/year at national volume). Azure OpenAI's automatic
   caching (reads at 0.1×, no write premium) benefits the gpt-5.1 path on the same prefix, so caching
   narrows absolute costs on both sides more than it moves the ratio.
3. **Model choice within each family.** Sonnet 5 lists cheaper ($2/$10) but its tokenizer offsets much of
   that on input-heavy workloads. Haiku 4.5 for the scoring path carries no self-hosting burden; its
   fitness for structured groundedness scoring should be validated before adoption.
4. **Throughput.** National peak is ~44 QPM × ~14.6K input tokens ≈ **645K input TPM** — the default
   Foundry pay-as-you-go quota (40 RPM / 40K ITPM for Sonnet-tier) is an order of magnitude short, while
   the Enterprise/MCA-E tier (10M ITPM) is ample; the quota path needs written confirmation. Claude's
   cache-aware ITPM (cache reads don't count toward the limit) compounds with lever 2: caching buys
   throughput headroom as well as cost.
5. **Data residency.** The evaluation ran on Azure (Foundry), but Foundry routes Claude globally (its only
   data-zone option is US, at 1.1×) — **strict UK-resident processing for Claude exists today only via AWS
   Bedrock eu-west-2 in-region** (Sonnet 4.6 / Opus 4.6), at a ~10% regional-endpoint premium and with the
   AWS Converse/InvokeModel API shape rather than the native Messages API measured here. On the gpt-5.1
   side there is no UK-resident processing option at all (EU Data Zone at best, from two regions). If UK
   residency is a hard requirement, the Claude path implies a platform move to Bedrock, and the batch and
   caching levers above must be re-assessed on that platform (batches unavailable; caching supported with a
   1,024-token minimum).

## 7. Assumptions and sensitivities

- FX £1 = $1.27; current published list prices; billed-vs-list uplift ×1.2 observed on production billing
  (the CCU equivalent needs confirming on a real invoice).
- Volumetrics per §2; document-ingestion, vector-DB and function-app costs (£314K/year) are
  provider-independent and unchanged by the model choice.
- Measured token counts reflect the evaluation retrieval configuration (30 chunks post-MMR) and the
  current candidate system prompt; production configuration changes move input tokens roughly linearly
  through every figure above.
- No reasoning/thinking tokens in the measured runs. The conviction-screen recall follow-up may motivate
  non-zero reasoning effort on gpt-5.1, which would raise its output costs; Claude's numbers already
  reflect its default (thinking off) configuration.
- Scoring figures are estimates from the pilot's scoring token shape (±12% tokenizer adjustment applied on
  Claude), pending measured validation of scoring quality on both candidates.
