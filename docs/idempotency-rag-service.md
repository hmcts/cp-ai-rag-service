# Idempotency in the RAG Service — Effectively-Once Message Processing

## Purpose

This page describes a **stand-alone change** to make the RAG Service's asynchronous processing *idempotent*, so that a message delivered more than once is not processed — and billed — more than once. It covers the background, how the current implementation falls short, the proposed design, and the concrete code and data changes.

## Background

### Asynchronous, queue-driven processing

The RAG Service performs its heavy work asynchronously. Two flows are queue-driven — **answer generation** and **document ingestion**. In both, an HTTP request first records an initial row in Table Storage and places a message on an Azure Storage Queue; a queue-triggered function then performs the expensive work (LLM calls, embeddings, Document Intelligence) and records the outcome back on the same row.

### At-least-once delivery

Azure Storage Queues — like most messaging systems — guarantee **at-least-once** delivery, not exactly-once. When a worker picks up a message it becomes invisible for a *visibility timeout*; if the worker does not delete it within that window (slow processing, a crash, or a deliberate retry), the message becomes visible again and is **redelivered**. Redelivery is a normal, expected part of the model — not a fault.

### Why duplicate processing is expensive here

Processed naively, each redelivery repeats the full pipeline — an LLM completion for answer generation, or Document Intelligence plus embeddings for ingestion. These are the service's most expensive operations, billed per token or per page. A duplicate therefore means **paying twice for the same result**; for answer generation it additionally enqueues a second downstream scoring job (a further LLM cost). At national-rollout volumes this is a material, recurring overspend rather than an edge case.

## The two asynchronous flows

### Answer generation (asynchronous)

1. `POST /answer-user-query-async` (`InitiateAnswerGeneration`) validates the request, writes an `ANSWER_GENERATION_PENDING` row keyed by a generated `transactionId`, enqueues a message, and returns the `transactionId`.
2. The queue-triggered `AnswerGeneration` worker embeds the query, retrieves chunks from AI Search, calls the LLM to generate the answer, persists the answer to Blob Storage, updates the row to `ANSWER_GENERATED` (or `ANSWER_GENERATION_FAILED`), and enqueues a scoring message.
3. `GET /answer-user-query-async-status/{transactionId}` returns the result once it is ready.

### Document ingestion

1. `POST /document-upload` (`InitiateDocumentUpload`) records an `AWAITING_UPLOAD` row keyed by `documentId` and returns a write-only SAS upload URL.
2. The client uploads the file; a blob trigger (`DocumentUploadCheck`) validates the size, updates the row to `AWAITING_INGESTION`, and enqueues an ingestion message.
3. The queue-triggered `DocumentIngestion` worker runs Document Intelligence to extract text, chunks it, generates embeddings, uploads the chunks to the AI Search index, and records `INGESTION_SUCCESS` (or `INGESTION_FAILED`).

In both flows the business key (`transactionId` / `documentId`) is stable and is already used as the Table Storage partition and row key — a natural idempotency key.

## How the current implementation is not idempotent

### No de-duplication gate in the workers

Neither worker checks whether the work has already been done before starting. `AnswerGeneration` runs embed → search → **LLM** → persist on *every* delivery; `DocumentIngestion` runs Document Intelligence → **embeddings** → index on every delivery. The status row is read only by the polling endpoint, never by the worker before it begins the expensive work.

### The answer-generation visibility timeout is shorter than the work

The answer-generation queue's `visibilityTimeout` is **30 seconds** — shorter than a typical embed + search + LLM completion. Once processing exceeds 30s the message reappears while the original is still running, and because the host processes several messages at once (`batchSize` = 4), a **duplicate runs in parallel with the original**, producing two LLM bills and two scoring messages for a single request. (Ingestion uses a 5-minute timeout and is less exposed, but exhibits the same behaviour on any redelivery.)

### State writes are not atomic

All Table Storage writes are unconditional, last-writer-wins upserts (no ETag / optimistic concurrency), and the status row stays `*_PENDING` for the entire in-flight duration — it only flips to a terminal value at the very end. There is therefore no in-progress marker to claim against, and no way for one worker to detect that another is already processing the same key.

### The retry mechanism deliberately reuses redelivery

Redelivery is *also used on purpose*: when an answer comes back citation-degraded, the worker rethrows to force a fresh attempt on the next delivery, up to the maximum dequeue count. Any idempotency solution must therefore **suppress accidental duplicates while still permitting these intentional retries**.

## Proposed change

### Goal: effectively-once processing

Exactly-once delivery is not achievable with distributed messaging. The standard, achievable target is **effectively-once = at-least-once delivery + an idempotent consumer**: accept that a message may arrive more than once, and make processing safe and cheap when it does. The solution is anchored on the existing idempotency key (`transactionId` / `documentId`).

### A reusable idempotency guard

Introduce a small shared primitive, `runOnce(key, work)`, that wraps the expensive work in each worker:

1. **Read** the current state for the key.
2. **If already terminal**, skip — complete the message without re-running the LLM/embeddings and **without re-enqueuing scoring**.
3. **Otherwise claim** the key (see below); if the claim succeeds, **run** the work and write the terminal outcome. On failure, release the claim and rethrow, so the existing retry behaviour is preserved.

Because the guard keys on the persisted state row rather than on any queue feature, it is simple, testable, and used identically by both flows.

### Atomic claim via ETag / If-Match

To stop two concurrent deliveries both processing the same key, the guard uses **optimistic concurrency** on the status row. Every Table Storage row carries an **ETag** — a version token that changes on every write. A worker reads the row (and its ETag), then transitions it to an internal *in-progress lease* using an **If-Match** conditional write: the update applies only if the row's ETag is unchanged. The first worker wins and the row's ETag rolls forward; a concurrent duplicate's conditional write is rejected (HTTP 412) and it backs off. A lease timestamp lets a stale lease — left by a crashed worker — be safely reclaimed by a later delivery via the same conditional write.

The same rule applies at **completion**: the terminal status write is conditioned on the ETag the worker captured when it claimed the lease. The claim ETag therefore acts as a *fencing token* — a worker whose lease has since been reclaimed by another delivery holds a stale ETag, so its late terminal write is rejected (412) and it cannot overwrite the outcome. For the same reason, the scoring message must be enqueued **only after** the conditional terminal write succeeds.

The in-progress lease is held in two **internal** columns and does **not** introduce a new public status value, so there is no API contract change.

### Reducing duplicates at the source

Independently, raise the answer-generation `visibilityTimeout` above the worst-case single-attempt processing time, so that healthy processing rarely triggers a redelivery in the first place. The guard remains the safety net for the duplicates that still occur (crashes, genuine overruns).

### Worked example — one row through its lifecycle

For `transactionId = 7f3a…` (answer generation):

1. **Submitted:** status `ANSWER_GENERATION_PENDING`, no lease.
2. **Worker A claims:** conditional write (If-Match) sets `LeaseOwner = A`, `LeaseExpiresAt = now + TTL`; status stays `PENDING`. A starts the LLM call.
3. **Duplicate B arrives** (visibility timeout expired): reads the row, sees a live lease (`LeaseExpiresAt` in the future) → backs off, does no work.
4. **A completes:** conditional write sets status `ANSWER_GENERATED`, persists the answer, enqueues scoring **once**.
5. **Any later duplicate:** reads status `ANSWER_GENERATED` → terminal → skips (no LLM, no scoring).
6. **Crash variant:** if A dies mid-work, a later delivery finds the lease expired and re-claims it atomically, then proceeds. (**Expensive operation will be retried depending on when the crash occurred.**)
7. **Expired-lease overlap variant (slow A, not a crash):** A is still running when its lease expires. A later delivery **B finds the lease expired and re-claims it** via the conditional write — the ETag rolls forward — then completes: status `ANSWER_GENERATED`, scoring enqueued once by B. When A eventually finishes, its terminal write is conditioned on the ETag it captured at claim time, which is now stale, so it is **rejected (HTTP 412)**. A discards its result and completes its message **without enqueuing scoring** — B's outcome stands. Two things to note: both A and B paid for an LLM call (the guard fences state and scoring, not the duplicated compute — sizing the lease TTL above worst-case processing, or renewing it mid-work, is what limits that; see Open questions), and if A persisted its answer blob before losing the race, that overwrite is benign — both answers were generated from the same inputs and the row's terminal status remains B's.

## The actual change

### Codebase touched

| Area | Change |
|---|---|
| Shared (`ai-document-shared-artefacts`) | New `IdempotencyGuard` (`runOnce`) and a per-flow `StatusStore` abstraction. |
| Shared table layer | Add a conditional (If-Match) update method to `TableService`; surface the row ETag through `AnswerGenerationTableService` and `DocumentIngestionOutcomeTableService`; add the two lease fields to `GeneratedAnswer`, `DocumentIngestionOutcome`, and `StorageTableColumns`. |
| Answer-generation worker | Wrap the pipeline in `runOnce(transactionId, …)`, gating before the embed/search/LLM step. |
| Ingestion worker | Wrap the pipeline in `runOnce(documentId, …)`, gating before Document Intelligence. |
| Configuration | Raise the answer-generation `visibilityTimeout` in `host.json`. |

### Tables and columns

- **No new tables** — the change reuses the two existing status tables.
- **Two new columns per status table**, both internal (not part of the public API):
  - `LeaseOwner` — a claim token identifying the worker holding the in-progress lease.
  - `LeaseExpiresAt` — when the lease becomes reclaimable.
- The **ETag is an intrinsic Table Storage property**, not a stored column — it requires no schema change.

### Configuration

- Answer-generation `visibilityTimeout` raised from 30 seconds to 5 minutes (matching ingestion). Note: in the Functions queues extension (bundle `[4.*, 5.0.0)`), `visibilityTimeout` is documented as the retry delay applied when a message's processing *fails* (is rethrown); in-flight invisibility of a message being processed is host-managed. The raise therefore primarily spaces out retries and crash redeliveries — the idempotency guard is the protection against duplicates either way. Pending empirical confirmation via the integration tests.
- `IDEMPOTENCY_LEASE_TTL_SECONDS` (new, default `300`): how long a claimed lease stays live before a redelivery may reclaim it. Sizing must satisfy `worst-case single attempt ≤ TTL < visibilityTimeout × (maxDequeueCount − 1)` — the lower bound stops a healthy slow worker being reclaimed prematurely; the upper bound guarantees a crashed leaseholder's lease expires while redelivery budget remains, so a later delivery can reclaim and finish the work.

### What is not changing

- **No API / OpenAPI contract change** — the public status values are unchanged; the lease is internal.
- **The citation-guard retry behaviour is preserved** — intentional retries still re-run; only accidental duplicates are suppressed.
- The scoring pipeline is unchanged, except that it is no longer double-triggered by a duplicate.

## Verification

- **Guard unit tests:** skips when terminal; claims when free; rejects the losing concurrent claim (412); reclaims a stale lease; preserves retry on `work` failure.
- **Worker unit tests:** a duplicate for an already-completed key performs no LLM/embedding call and no scoring enqueue; a genuine (non-terminal) retry still runs.
- **Integration tests:** deliver the same message twice — sequentially after completion, and concurrently with the original — and assert exactly one expensive execution and one scoring message; confirm a slow generation no longer triggers a duplicate once the visibility timeout is raised.

## Resolved questions

- **Terminal `*_FAILED` rows are skipped** (as recommended): they are only written after the retry budget is exhausted, so reprocessing them would bypass the retry cap. Recovery is by re-submitting a new request. For ingestion, `FILE_SIZE_OVER_LIMIT` is also treated as terminal (defensive — such a document is never enqueued).
- **Fixed lease TTL, no mid-work renewal (v1)**: `IDEMPOTENCY_LEASE_TTL_SECONDS` (default 300s), sized above worst-case single-attempt processing but below the total redelivery span (see Configuration). Renewal can be added later if lease overruns show up in telemetry.

## Implementation refinements (deviations from the original design text)

- **Lease fields are not on the public entity POJOs.** `GeneratedAnswer`/`DocumentIngestionOutcome` feed the public status-check read paths; the lease is internal, carried by an internal `LeaseSnapshot` read model. `StorageTableColumns` holds the two column constants.
- **`StatusStore` is one interface (`IdempotencyStatusStore`) implemented directly by the two existing table services**, not new per-flow classes.
- **"Backs off" = rethrow-to-redeliver, not drop.** Dropping would delete the message — the only redelivery vehicle if the leaseholder later crashes. Each back-off consumes dequeue budget.
- **Exhaustion against a live lease writes nothing.** If the final delivery finds a live lease, it must not overwrite a possibly-completing leaseholder with `*_FAILED`; it logs a WARN and completes. Residual risk: a crashed leaseholder leaves the row `PENDING` with no further redeliveries — alert on the WARN.
- **Lease release is an epoch sentinel** (`LeaseExpiresAt = 1970-01-01T00:00Z`) via a conditional MERGE — Azure Tables MERGE cannot remove a property. A 412 on release is swallowed (a reclaimer's lease stands).
- **All conditional writes use MERGE (not REPLACE)** so the scoring function's later groundedness-score merge can never be erased; `recordGroundednessScore` itself stays unconditional (it runs post-terminal and overwriting a score with a freshly computed one is idempotent).
- **Failure-at-exhaustion writes are re-checked, never blind.** When the final delivery fails *without* holding a claim (e.g. the status-row read itself erred), the worker re-reads the row and records FAILED only if it is non-terminal and unleased, fenced on the freshly read ETag — it never last-writer-wins over a completed or in-progress duplicate. If even the re-check fails, nothing is written.
- **Everything fallible runs before the fenced terminal write.** In the answer worker the eval blob and the scoring message body are prepared *before* the row flips terminal, because a terminal row makes any redelivery skip: a tail step failing after the terminal write could never be retried.

## Known limitations

- **Crash between the fenced terminal write and function completion** (answer flow): the scoring message rides the Functions output binding, sent when the invocation returns. A host crash in that tiny window loses the scoring enqueue permanently (the redelivery skips the now-terminal row). Accepted: scoring is an observability signal, not user-facing state.
- **Unfenced writers on the same rows can fence out a completing worker** (ingestion flow): the blob trigger (`DocumentUploadCheck`) still writes `AWAITING_INGESTION` unconditionally. If a caller re-uploads to the still-valid SAS URL while ingestion is in flight, that write rolls the row's ETag and the in-flight worker's fenced `INGESTION_SUCCESS` is rejected — its (already indexed) result row write is discarded and the *second* queue message completes the status instead. Net behaviour matches the pre-guard double-processing cost for re-uploads; a follow-up could make the blob trigger lease-aware.
- **A defensively created status row is minimal.** If the ingestion status row is missing (an anomaly — the queue message is only enqueued after the row exists), the guard creates a bare `AWAITING_INGESTION` row without `DocumentFileName`/`DocumentMetadata`/`SupersededDocuments`; ingestion then proceeds without superseded-document deactivation and the status row lacks those display columns.
