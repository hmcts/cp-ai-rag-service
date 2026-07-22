# Priority & Consumer Fairness — Design Document

| | |
|---|---|
| **Status** | Draft for review |
| **Service** | AI RAG Service (`cp-ai-rag-service`) |
| **Related** | *Model Provisioning and Capacity Strategy: National Rollout* (Confluence, CROWN); *Infrastructure Cost Model — Pilot Usage and National Rollout Projections* |
| **Scope** | Async answer-generation and document-ingestion pipelines |

---

## Table of Contents

1. [Overview](#overview)
2. [Background & Context](#background--context)
3. [Goals & Non-Goals](#goals--non-goals)
4. [Key Concepts & Terminology](#key-concepts--terminology)
5. [Current Architecture](#current-architecture)
6. [Design Principles & Constraints](#design-principles--constraints)
7. [Proposed Design](#proposed-design)
   - 7.1 [Priority signal (API)](#71-priority-signal-api)
   - 7.2 [Model routing & overflow](#72-model-routing--overflow)
   - 7.3 [Message processing: order & fairness](#73-message-processing-order--fairness)
   - 7.4 [Concurrency & TPM control](#74-concurrency--tpm-control)
8. [Concurrency & host.json Behaviour](#concurrency--hostjson-behaviour)
9. [Iterative Delivery Plan](#iterative-delivery-plan)
10. [Open Decisions](#open-decisions)
11. [Verification & Testing](#verification--testing)
12. [Risks & Mitigations](#risks--mitigations)
13. [Appendix: Affected Components](#appendix-affected-components)

---

## Overview

The AI RAG Service will move from a single-tier, best-effort processing model to a **priority-aware** one, so that time-sensitive legal queries and urgent document ingestions are served ahead of routine traffic and — where required — by a dedicated, deterministic-latency model tier. The design also introduces **consumer fairness**, ensuring that as multiple consuming services onboard, no single consumer can monopolise capacity.

This document describes the target design and a four-iteration delivery plan that reaches it incrementally, with each iteration independently shippable.

## Background & Context

The *Model Provisioning and Capacity Strategy: National Rollout* commits the service to a **Priority-First hybrid**: a small, always-on high-priority model tier (deterministic latency; potentially provisioned/PTU-backed once volumes justify it) serves roughly the top 20% of urgent traffic, while the bulk of the national workload runs on elastic, pay-as-you-go standard capacity. Standard capacity is cost-efficient and naturally scalable but offers no throughput or latency guarantee.

The service today has **no concept of priority**. All async answer-generation requests share one queue and one model; all document ingestions share one queue. Realising the strategy requires priority to be a first-class attribute that flows from the API through message processing to model selection.

A second, related need emerged during design: the service will in future be called by **multiple consuming services**, and it must schedule work **fairly** across them so that one busy consumer cannot starve the others.

## Goals & Non-Goals

**Goals**

- Introduce an explicit **priority** attribute (`HIGH` / `STANDARD`) to the async answer-generation and document-ingestion APIs.
- Route high-priority answer-generation to a dedicated **high-priority model** with automatic overflow to the standard model.
- Ensure high-priority work is **processed ahead of** standard work in both pipelines.
- Provide **consumer fairness** — round-robin-style scheduling that prevents any single consumer overwhelming a lane.
- Allow each lane's throughput to be **capped to its model's token-per-minute (TPM) ceiling**, and scaled independently.
- Keep the design **provisioning-agnostic** so the high-priority model can start as an ordinary deployment and later be backed by reserved (PTU) capacity with no code change.

**Non-Goals**

- The **synchronous** answer endpoint (`AnswerRetrieval`) is test-only and is out of scope.
- **Groundedness scoring** remains on its existing (standard) path — it is background, latency-tolerant work and is deliberately excluded from priority routing.
- Ingestion priority is **expedite-only**; it does not change the Document Intelligence or embedding compute path.
- This document does not select a specific PTU size or reservation term — that is covered by the cost-model and strategy documents.

## Key Concepts & Terminology

| Term | Meaning |
|---|---|
| **Priority tier** | `HIGH` or `STANDARD`. A property of the *request/query*, defaulting to `STANDARD`. |
| **High-priority / standard-priority model** | Two independently-configured model deployments. Naming is deliberately provisioning-agnostic — the high tier *may* later be reserved (PTU) capacity, but the code never says so. |
| **Model routing (Axis A)** | Choosing *which model* serves a request based on priority. Applies to answer-generation only. |
| **Processing order (Axis B)** | Ensuring high-priority *messages* are handled ahead of standard ones. Applies to both pipelines. |
| **Consumer fairness** | Scheduling work so that each consuming service makes progress regardless of another consumer's volume. |
| **Overflow** | When the high-priority model is at capacity (HTTP 429), the request falls back to the standard model rather than failing. |

Priority therefore decomposes into two orthogonal mechanisms — **model routing** and **processing order** — plus the cross-cutting concern of **consumer fairness**. Recognising these as separable is what makes an incremental delivery possible.

## Current Architecture

**Async answer-generation** (three functions + one worker):

```
POST /answer-user-query-async  →  InitiateAnswerGeneration
        → writes PENDING row (Table Storage)
        → enqueues AnswerGenerationQueuePayload (Storage Queue)
        → returns transactionId

[queue] → AnswerGeneration (worker): embed → search → LLM → persist (Blob) → update row → enqueue scoring
GET /answer-user-query-async-status/{id} → GetAnswerGeneration (poll)
```

**Document ingestion**:

```
POST /document-upload → InitiateDocumentUpload → PENDING row + SAS URL
   (client PUTs file to SAS URL)
[blob] → DocumentUploadCheck: size check → update row → enqueue QueueIngestionMetadata
[queue] → DocumentIngestion: Document Intelligence → chunk → embed → index (AI Search)
```

**Messaging & platform:** all queues are **Azure Storage Queues** (FIFO, no native priority). Each module is its own function app. Storage access is **managed-identity only**. Request/response models are **generated from an external OpenAPI spec** (`hmcts/api-cp-ai-rag`) and must not be hand-edited.

## Design Principles & Constraints

- **Contract-first.** API changes are made in the external spec repo first, then models are regenerated and the version bumped in `ai-document-shared-artefacts/pom.xml`.
- **Provisioning-agnostic naming.** Code, configuration keys, and message fields refer to *high-priority* and *standard-priority* models — never to PTU/PAYG. The backing of each tier is a deployment/config choice.
- **Backward compatibility.** `priority` is optional and defaults to `STANDARD`; existing callers are unaffected.
- **Managed identity throughout.** Any new messaging infrastructure authenticates via managed identity and Azure RBAC, consistent with the existing storage stance.
- **Scoring unchanged.** The scoring pipeline stays on Storage Queues; this is a documented exception, not architectural drift.
- **Incremental and reversible.** Each iteration delivers value and de-risks the next; early iterations use existing infrastructure.

## Proposed Design

### 7.1 Priority signal (API)

An optional `priority` enum (`HIGH` | `STANDARD`, default `STANDARD`) is added to both `answerUserQueryRequest` and `documentUploadRequest` in the OpenAPI spec. Priority is a property of the query/document, not of the transport, and flows through the system as follows:

- It is read at the HTTP entry point, carried on the queue/topic message, and persisted on the Table Storage row for observability.
- For answer-generation it additionally drives model routing; for ingestion it drives expedite-only scheduling.

Because any caller could set `HIGH`, **authorisation of who may request `HIGH`** is a governance control applied at the API gateway (see [Open Decisions](#open-decisions)).

### 7.2 Model routing & overflow

`ResponseGenerationService` holds two `ChatService` instances — a high-priority and a standard-priority model — each configured by its own endpoint/deployment settings. A `priority` parameter selects the tier per request. If no distinct high-priority model is configured, both resolve to the standard model, so routing is a safe no-op until the high tier exists.

**Overflow:** if the high-priority model returns HTTP 429 (at capacity), the request falls back in-process to the standard-priority model. This guarantees no request is hard-blocked and is independent of the messaging layer. The tier used and any overflow event are emitted as metrics.

### 7.3 Message processing: order & fairness

High-priority messages must never queue behind standard ones, and no single consumer may monopolise a lane. Neither Azure Storage Queues nor Service Bus provide a native "priority" that reorders delivery — priority is always modelled as **separate destinations**. The target design uses **Azure Service Bus**:

- One **topic per pipeline** (`answer-generation`, `document-ingestion`).
- Two **subscriptions** per topic — `high` and `standard` — with SQL filters on a `priority` message property. Each subscription is drained by its own trigger, so high and standard are physically separated and a standard backlog cannot delay high.
- **Session-enabled** subscriptions keyed by **`sessionId = consumerId`**. Bounded concurrent sessions mean a consumer that floods a lane occupies a single session slot while others keep progressing — delivering the required fairness. This session capability is the specific reason Service Bus is chosen over Storage Queues, which cannot schedule fairly across consumers.

```
                         ┌── subscription: high  (priority='HIGH')  → high-lane worker
publish (priority,       │
 sessionId=consumerId) ──┤
   to topic              │
                         └── subscription: standard (default)        → standard-lane worker
```

### 7.4 Concurrency & TPM control

The high-priority model is expected to have a **lower TPM ceiling** than the standard model (a small reserved pool vs. large elastic capacity). Each lane's total in-flight concurrency must therefore be capped so that `concurrency × avg-tokens-per-request ÷ avg-duration ≤ that model's TPM`. Because Azure Functions `host.json` concurrency is **app-global and per-instance**, achieving different, independently-tuned caps per lane requires **separate function apps**, each with its own `host.json` and a **hard scale-out cap**. This is detailed next.

## Concurrency & host.json Behaviour

Understanding how the Functions host applies concurrency is central to the design:

- **Priorities never share a runtime queue.** A published message is filter-copied into exactly one subscription; each subscription has its own trigger. High and standard are drained by separate triggers and never pulled from one ordered list — physical separation, not a priority sort, is what prevents starvation.
- **host.json is respected per trigger, independently.** Each trigger enforces the configured concurrency on its own; the high and standard consumers do not share a pool. With session-enabled subscriptions, per-instance in-flight ≈ `maxConcurrentSessions × maxConcurrentCallsPerSession` (with per-session = 1 to preserve in-session order).
- **Total = per-instance × instance count.** The host scales out; Service Bus target-based scaling derives the scale target from the same `host.json` values. To bound *total* in-flight (and thus stay under a model's TPM), you must cap **both** per-instance concurrency **and** the maximum instance count (`functionAppScaleLimit`, or a fixed-instance plan).
- **host.json is app-global.** A single function app applies one `extensions.serviceBus` block to all its Service Bus triggers. Giving the high lane a *different* (smaller) cap than standard therefore requires deploying the lanes as **separate function apps**.
- **Lock duration must exceed the LLM call time.** `maxAutoLockRenewalDuration` must comfortably exceed a slow generation, or a high-priority message loses its lock and is redelivered/duplicated.

**Sizing intuition.** The high lane is ~6 QPM at peak; at ~15 s/request that is only ~2–4 concurrent requests, peaking modestly. A cap of ~4–6 total in-flight comfortably serves it within a small reserved pool — a deliberately tiny number, and precisely why it cannot share an app-global concurrency value with the high-volume standard lane. Caps should be **calibrated empirically** (capacity planner plus load-test to just below the onset of 429s, with margin), because the token/latency interplay is model-specific. Bounded concurrency is the primary control; 429-overflow to the standard model is the backstop.

## Iterative Delivery Plan

Each iteration is independently shippable and builds on the previous.

### Iteration 1 — API support for the priority model

**Goal:** the contract accepts `priority`; answer-generation routes to the correct model; priority is carried and persisted end-to-end. Message ordering is unchanged (single FIFO queue per pipeline).

- Add optional `priority` (`HIGH`|`STANDARD`, default `STANDARD`) to `answerUserQueryRequest` and `documentUploadRequest`; regenerate models; bump the spec version.
- Introduce a shared `Priority` enum and high-/standard-priority model configuration (high falls back to standard when unset).
- Implement dual-model routing with 429 overflow in `ResponseGenerationService`; emit tier/overflow metrics.
- Carry `priority` on both queue payloads and persist it on both Table Storage rows.

**Outcome:** high-priority answers use the high-priority model with overflow; ingestion records priority (no behavioural change yet).

### Iteration 2 — Priority-aware processing on existing Storage Queues

**Goal:** high-priority messages are processed ahead of standard, with **zero new infrastructure**; ingestion expedite is realised.

- Add a second, high-priority queue per pipeline; producers route by priority at enqueue using dual output bindings.
- Add dedicated high and standard workers per pipeline. Each queue has independent polling, so high is never stuck behind a standard backlog.

**Known limits (motivating later iterations):** Storage Queue `host.json` config is app-global, and Storage Queues provide no consumer fairness — one consumer can still monopolise a lane.

**Outcome:** priority ordering and ingestion expedite delivered on existing infrastructure.

### Iteration 3 — Service Bus topics + sessions for consumer fairness

**Goal:** prevent any single consumer overwhelming a lane; enable fair scheduling across multiple consumers.

- Provision a Service Bus namespace (managed-identity RBAC). Topic per pipeline with `high`/`standard` filtered, session-enabled subscriptions (`sessionId = consumerId`).
- Producers publish once with a `priority` property and `sessionId`; consumers switch to Service Bus topic triggers.
- Migrate redelivery/citation-guard to Service Bus semantics (peek-lock, delivery count, managed dead-letter queue); lock renewal exceeds LLM call time.
- Complementary per-consumer inbound throttling at the API gateway.

**Outcome:** consumer-fair scheduling, declarative routing, managed dead-lettering and per-subscription metrics.

### Iteration 4 — Independent packaging & scaling per lane

**Goal:** cap each lane to its model's TPM ceiling and scale the lanes independently.

- Extract the Service Bus worker into its own thin deployable module. It is written once and **deployed twice** (high/standard) with **config-only** differences — subscription name, topic, and connection are resolved from app settings, and model routing is data-driven on the message priority.
- Apply per-lane concurrency (`maxConcurrentSessions`, per-session = 1) **and** a hard scale-out cap so total in-flight is deterministic. The high lane preferably runs on a fixed-instance plan, matching always-on reserved capacity with no cold start.

**Outcome:** high and standard lanes scale and throttle independently; the high lane cannot exceed its model's TPM ceiling; reserved (PTU) capacity can be slotted under the high lane as pure configuration.

## Open Decisions

| # | Decision | Recommendation |
|---|---|---|
| 1 | **Consumer identity** (session key / fairness unit) | The API-gateway subscription / authenticated client id; fallback to a `consumerId` claim or header. |
| 2 | **Service Bus tier** | Standard; Premium only if private-endpoint/VNet isolation is mandated. |
| 3 | **High-priority model backing** | Start as a second standard-tier deployment (or the same deployment); move to reserved (PTU) capacity when volumes justify — no code change. |
| 4 | **High-lane plan type** | Fixed-instance plan (most deterministic, pairs with always-on reserved capacity) vs. Elastic Premium with a scale-out cap. |
| 5 | **`HIGH` authorisation** | Restrict which consumers may request `HIGH` at the API gateway to protect the small high-priority pool. |

## Verification & Testing

- **Iteration 1 — unit + contract:** model selection (high for `HIGH`, standard for `STANDARD`); overflow falls back on a mocked 429; both tiers resolve to standard when no high model is configured; payload/entity round-trips include `priority`; contract check confirms the optional field and default.
- **Iteration 2 — integration** (Storage Queue emulator + Testcontainers): a `HIGH` request completes while the standard queue is flooded; a `HIGH` document ingests ahead of a standard backlog.
- **Iteration 3 — integration** (Service Bus emulator): two consumers, one flooding — the other keeps progressing (fairness); induced failures land in the dead-letter queue after the delivery-count limit; the lock survives a slow (mocked) LLM call.
- **Iteration 4 — load test:** total in-flight on the high lane stays at `maxInstances × maxConcurrentSessions`; 429s trigger overflow rather than failure; the standard lane is unaffected; the single worker artifact runs correctly in both deployments with only app-setting differences.

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| High-priority model TPM ceiling exceeded under load | Bounded per-lane concurrency (primary) + 429 overflow to standard (backstop) + alerting on 429 rate and queue depth. |
| Any caller sets `HIGH` and swamps the small pool | Gateway-enforced authorisation of who may request `HIGH` (Decision 5). |
| Scoring's own TPM ceiling on the standard path | Tracked separately; scoring is latency-tolerant and can be throttled/queued, or moved to a self-hosted small model per the cost model. |
| Service Bus local-dev / test maturity vs. Storage Queues | Introduced only in Iteration 3, with the emulator wired into the integration-test module; earlier iterations use the mature Storage Queue tooling. |
| Redelivery semantics change (poison queue → dead-letter) | Preserve the existing citation-guard `deliver`/`reject` policy; validate lock renewal against real LLM latencies. |
| Mixed messaging model (Service Bus + Storage-Queue scoring) | Deliberate and documented; scoring has no priority/fairness need. |

## Appendix: Affected Components

| Area | Components |
|---|---|
| **API contract** (external) | `answerUserQueryRequest`, `documentUploadRequest` in `hmcts/api-cp-ai-rag`; version bump in `ai-document-shared-artefacts/pom.xml`. |
| **Shared** | `Priority` enum; `SharedSystemVariables` (standard + high-priority model config, topic/subscription/queue names); reuse `ChatServiceFactory`, `AzureOpenAiClientFactory`, `ObjectMapperFactory`. |
| **Answer-generation** | `InitiateAnswerGenerationFunction`, `AnswerGenerationFunction` (→ topic trigger; extracted worker module in Iter 4), `AnswerGenerationQueuePayload`, `ResponseGenerationService`, `GeneratedAnswer` + table service. |
| **Ingestion** | `DocumentUploadFunction`, `DocumentUploadService`, `DocumentBlobTriggerFunction`, `QueueIngestionMetadata`, `DocumentIngestionFunction`, `DocumentIngestionOutcome` + table service. |
| **Platform** | Service Bus namespace + RBAC (Iter 3); per-app `host.json` concurrency + scale caps (Iter 4); `local.settings` samples; integration-test topology (Storage Queue then Service Bus emulator). |
| **Excluded** | Scoring pipeline (`ai-document-answer-scoring-function`); synchronous `AnswerRetrieval` endpoint. |
