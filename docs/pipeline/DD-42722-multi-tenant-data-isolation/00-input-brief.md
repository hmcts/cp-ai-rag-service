# Input Brief — Multi-Client Data Isolation for CP AI RAG Service

**Source:** Confluence page 1973297793 (space CROWN, "Multi-Tenant Data Isolation for CP AI RAG Service", v10) + codebase verification (2026-07-20) + stakeholder decisions (Mahesh, 2026-07-20).

**Naming decision (supersedes the Confluence page):** the tenancy attribute is **`clientId`**, not `tenantId`. All schema fields, code identifiers, partition keys and telemetry dimensions use `clientId`.

---

## 1. Problem statement (from Confluence, corrected)

The CP AI RAG Service is single-consumer by construction: APIM (`api-cp-ai-rag-secure`) validates a single Entra ID client application's JWT; the function backends receive no caller identity. All ingested documents, AI Search chunks, Table rows and blobs live in one shared namespace. Any caller in possession of a UUID (documentReference / transactionId) can read any other caller's status or answer payload.

Goal: per-client data isolation —
1. Identity at the edge: APIM (per AMP standards, design owned by another team, **not yet concrete**) identifies the consumer and passes a trusted client identifier downstream. Backends trust it because they only accept APIM traffic (function key + network restriction).
2. Isolation in the data plane: single AI Search index, single storage account, single set of tables retained. `clientId` becomes a top-level filterable Search field, the Table partition key, a blob path prefix, a non-optional leading clause on every search filter, and a queue-payload field.

Existing production corpus (single client) is migrated to a designated incumbent client ID before enforcement. Cut-over is feature-flag gated for reversibility.

## 2. Verified current state (codebase, 2026-07-20)

- **APIM:** `validate-azure-ad-token` against one allowed application-id; `subscriptionRequired=false`; per-operation policies inject the function key. No consumer identification.
- **HTTP functions:** `AuthorizationLevel.FUNCTION` only. Endpoints (contract-first, spec in `api-cp-ai-rag` repo): `POST /document-upload`, `GET /document-upload/{documentReference}`, `POST /answer-user-query` (sync), `POST /answer-user-query-async`, `GET /answer-user-query-async-status/{transactionId}`.
  - NOTE: the Confluence page references a `DocumentStatusCheckFunction` / `GET /document-status?document-name=…` endpoint — **it does not exist**. Only `DocumentStatusByReferenceFunction` exists in `ai-document-status-check-function`.
- **AI Search:** index `ai-rag-service-index` (`vector-db-index-schema.json`) — fields `id`, `chunk`, `chunkVector`, `documentFileName`, `documentId`, `pageNumber`, `chunkIndex`, `documentFileUrl`, `customMetadata` (caller-supplied key/value collection). No client field. Chunk `id` is a **random UUID** (`DocumentChunkingService`), so no cross-client key-collision risk in the index.
- **OData filter:** `AzureAISearchService.generateFilterExpression` builds `customMetadata/any(...)` clauses. Single-quote escaping **already exists** (`StringUtil.escapeODataStringLiteral`, doubles quotes) — the Confluence claim of an unescaped injection surface is stale. Remaining work: prepend a non-optional `clientId eq '…'` clause + regression tests.
- **Table Storage:**
  - `DocumentIngestionOutcome` (via `DocumentIngestionOutcomeTableService`): PartitionKey = RowKey = documentId.
  - `GeneratedAnswer` (via `AnswerGenerationTableService`): PartitionKey = RowKey = transactionId.
  - **`IdempotencyGuard` (shared artefacts) leases and fences on these rows** — claim/release/readForClaim all construct `TableEntity(key, key)` with lease columns `LeaseOwner`/`LeaseExpiresAt` and ETag fencing. Any partition-key change must make the whole `IdempotencyStatusStore` interface client-aware, and queue workers must recover `clientId` from the message before claiming a lease. (Not mentioned in the Confluence page at all.)
- **Blob Storage:** flat naming `{documentId}_{yyyyMMdd}.{ext}` built centrally in `DocumentBlobNameResolver` (regex parse back to documentId). Answer payload blobs: `llm-answer-with-chunks-{transactionId}.json`, `llm-input-chunks-{transactionId}.json`.
- **Queues/DTOs:** `QueueIngestionMetadata` (documentId, documentName, metadata, blobUrl, currentTimestamp); `AnswerGenerationQueuePayload` (transactionId, userQuery, queryPrompt, metadataFilter); `ScoringQueuePayload` (**filename only** — the actual scoring input is a `ScoringPayload` blob; the Confluence claim that the scoring worker reads tenant from the queue message needs correcting: clientId must ride the `ScoringPayload` blob and/or payload, and the scorer writes the groundedness score back to the `(clientId, transactionId)` row).
- **Duplicate check:** `DocumentUploadFunction.isDocumentAlreadyProcessed(documentId)` — currently global.
- **Telemetry:** `PublishScoreService` publishes `query_type` dimension = raw user query (separate PII concern); no client dimension.
- **Citation guard / idempotency env interplay:** async retries ride queue redelivery (maxDequeueCount 3) with lease release before rethrow — client threading must survive redelivery (it does naturally if clientId is in the queue message).

## 3. Stakeholder decisions (2026-07-20, override the page where they conflict)

| # | Decision |
|---|----------|
| D1 | Attribute named **`clientId`** everywhere (field, constant, partition key, telemetry dimension, blob prefix). |
| D2 | `documentId` uniqueness is **per client**: dedup check, overwrite path and chunk deletion scoped to `(clientId, documentId)`. Two clients may use the same documentId. |
| D3 | Cut-over: **queues drained first** — no in-flight messages at cut-over, so no intermediate-state data and no dual-write mode. Migration tool stamps clientId on all existing AI Search documents and Table rows before the enforcement flag flips. |
| D4 | AMP consumer-identity design is not concrete. **Assume** APIM identifies the consumer upstream and the identity reaches the function app in a recognised header (may become JWT/cookie later). **Encapsulate client-ID extraction in one abstract shared method** in `ai-document-shared-artefacts`, referenced by every HTTP-triggered function — a single point of change when AMP finalises. |
| D5 | The injected header is an **internal contract between APIM policy and function apps** — not part of the consumer-facing OpenAPI spec. HTTP functions return **401** when the identity is missing/invalid. |
| D6 | Eval harness (`ai-document-system-prompt-harness-eval`) impact: open — design should propose the simplest approach that keeps it working against a client-scoped index. |
| D7 | `clientId` format: **UUID assumed for now** (user to confirm; format constraint must be validated at the boundary since the value lands in OData literals, partition keys, blob paths, metric dimensions). |

## 4. Design elements retained from the Confluence page

- Top-level `clientId` field in the Search index (filterable, not searchable) — preferred over reusing caller-controlled `customMetadata` (trust-boundary separation).
- Table keying: `DocumentIngestionOutcome` → PK=clientId, RK=documentId; `GeneratedAnswer` → PK=clientId, RK=transactionId; clientId also persisted as a non-key column. Cross-client lookups return **404 (not 403)** to avoid existence leakage.
- Blob paths: new blobs at `c={clientId}/{documentId}_{yyyyMMdd}.{ext}` (and same prefix for answer-payload blobs); legacy blobs stay in place; blob-trigger supports both shapes transitionally; the Table row (not the path) is the authoritative ownership record.
- Queue payloads gain non-optional `clientId`.
- Telemetry gains a `client_id` dimension (per-client groundedness segmentation).
- Feature flag (e.g. `CLIENT_FILTERING_ENABLED`) gates enforcement; off = identity optional, filter omitted (rollback safety net).
- Logical isolation chosen over index-per-client / full silo, but clientId treated as a namespace so a client can later be migrated to its own index/silo via routing config only.
- Migration: add nullable field to live index → backfill chunks via mergeOrUpload (progress: `clientId eq null` count → 0) → Table rows copy-then-delete (idempotent, resumable; must skip/handle rows with live idempotency leases — prefer migrating only terminal rows after queue drain) → blobs left in place → flag flip per environment → onboard next client via APIM config only.
- Recommended hardening (scope decision pending): reject `metadataFilter` keys not on an allow-list so callers cannot filter on internal fields.

## 5. Out of scope

- APIM policy implementation (`cpp-azure-api-management` repo) — tracked as a coordination dependency; our side defines the internal header contract and enforcement behind the abstraction (D4).
- Per-client rate limits/quotas/analytics (AMP standards, later).
- Physical migration of legacy blobs; retirement of the dual-path resolver.
- Per-client index/silo migration runbook.
- `query_type` PII telemetry fix (flagged separately; adjacent but independent).
