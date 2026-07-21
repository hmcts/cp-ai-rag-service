# Requirements: Multi-Client Data Isolation (CP AI RAG Service)

## Context
The CP AI RAG Service is single-consumer by construction: APIM validates one Entra ID
application's JWT and the function backends receive no caller identity, so all documents,
AI Search chunks, Table rows and blobs share one namespace. Any caller holding a UUID
(`documentReference` / `transactionId`) can read any other caller's status or answer payload.
This initiative introduces per-client logical isolation across the data plane using a single
`clientId` attribute (see input brief §1–§4, decisions D1–D7). The existing production corpus
is migrated to a designated incumbent `clientId` before enforcement, which is feature-flag
gated for reversibility. Source of truth:
`docs/pipeline/DD-42722-multi-tenant-data-isolation/00-input-brief.md`.

Not applicable from the generic template: CQRS command/query separation and Spring Boot layering
— this is a multi-module Maven Azure Functions (Java) service.

## Actors
| Actor | Description |
|-------|-------------|
| Consuming client application | Entra ID app calling the API via APIM; identified upstream and carried downstream as `clientId`. Multiple clients post-onboarding. |
| APIM policy (edge) | Validates the caller and injects the trusted internal client-identity header. Design owned by another team (`cpp-azure-api-management`); not yet concrete (D4). |
| HTTP/Queue/Blob functions | Trust APIM-injected identity (function key + network restriction), enforce isolation in the data plane. |
| Platform/release operator | Runs migration/backfill tooling and flips the enforcement flag per environment. |
| Answer-scoring / telemetry consumer | Reads per-client groundedness segmentation via the `client_id` metric dimension. |

## Functional requirements
| ID | Requirement                                                                                                                                                                                                                                                                                                                                                             | Priority |
|----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| FR-1 | Provide a single abstract, shared client-identity extraction method in `ai-document-shared-artefacts`, referenced by every HTTP-triggered function, that reads `clientId` from the internal APIM-injected header (D4). It is the single point of change when AMP finalises the identity mechanism.                                                                      | Must |
| FR-2 | When enforcement is enabled, any HTTP request with a missing or invalid client identity is rejected with **401** (D5). No downstream processing occurs.                                                                                                                                                                                                                 | Must |
| FR-3 | Gate all isolation enforcement behind a feature flag (e.g. `CLIENT_FILTERING_ENABLED`). Flag off = identity optional and filter clause omitted (pre-existing behaviour); flag on = identity mandatory and filter applied.                                                                                                                                               | Must |
| FR-4 | Add a top-level `clientId` field to the AI Search index (`vector-db-index-schema-v2.json`): filterable, not searchable, distinct from caller-controlled `customMetadata` (trust-boundary separation).                                                                                                                                                                   | Must |
| FR-5 | `AzureAISearchService.generateFilterExpression` must prepend a non-optional leading `clientId eq '<clientId>'` clause to every search filter when enforcement is on. Existing single-quote escaping (`StringUtil.escapeODataStringLiteral`) is retained; no client value reaches an OData literal unescaped.                                                            | Must |
| FR-6 | Re-key Table Storage for per-client partitioning: `DocumentIngestionOutcome` → PK=`clientId`, RK=`documentId`; `GeneratedAnswer` → PK=`clientId`, RK=`transactionId`; `clientId` also stored as a non-key column.                                                                                                                                                       | Must |
| FR-7 | Make `IdempotencyStatusStore` / `IdempotencyGuard` client-aware: lease claim, release, fence (ETag/If-Match) and `readForClaim` operate on `(clientId, documentId)` / `(clientId, transactionId)` rows. Queue workers must recover `clientId` from the message before claiming a lease.                                                                                 | Must |
| FR-8 | Scope `documentId` uniqueness to `(clientId, documentId)` (D2): the dedup check (`DocumentUploadFunction.isDocumentAlreadyProcessed`), overwrite path and chunk deletion are all client-scoped. Two clients may reuse the same `documentId` without collision.                                                                                                          | Must |
| FR-9 | Prefix new blob paths with the client namespace: `c={clientId}/{documentId}_{yyyyMMdd}.{ext}` for uploaded documents and the same prefix for answer-payload blobs (`llm-answer-with-chunks-*`, `llm-input-chunks-*`). Path construction stays centralised in `DocumentBlobNameResolver`.                                                                                | Must |
| FR-10 | Provide dual-path resolution: the blob trigger and name resolver recognise both the new prefixed shape and the legacy flat `{documentId}_{yyyyMMdd}.{ext}` shape. The Table row (not the path) is the authoritative ownership record.                                                                                                                                   | Must |
| FR-11 | Thread a non-optional `clientId` through queue payloads: `QueueIngestionMetadata` and `AnswerGenerationQueuePayload` gain a `clientId` field so it survives redelivery (maxDequeueCount 3) on the async/citation-guard retry path.                                                                                                                                      | Must |
| FR-12 | Carry `clientId` to the scoring worker via the `ScoringPayload` blob and/or payload, **not** solely `ScoringQueuePayload` (which is filename-only). The scorer writes the groundedness score back to the correct `(clientId, transactionId)` row.                                                                                                                       | Must |
| FR-13 | Add a `client_id` dimension to telemetry published by `PublishScoreService`, enabling per-client groundedness segmentation. (Independent of the separate `query_type` PII concern.)                                                                                                                                                                                     | Must |
| FR-14 | Cross-client lookups (a `documentReference`/`transactionId` belonging to another client) return **404**, never 403, to avoid existence leakage.                                                                                                                                                                                                                         | Must |
| FR-15 | Provide migration/backfill tooling: add nullable `clientId` to the live index → backfill chunks via `mergeOrUpload` (progress measured by `clientId eq null` count → 0) → copy-then-delete Table rows (idempotent, resumable) → leave blobs in place. Rows with live idempotency leases must be skipped/handled; prefer migrating only terminal rows after queue drain. | Must |
| FR-16 | Support cut-over with drained queues (D3): no in-flight messages at cut-over, so no dual-write mode and no intermediate-state records. The migration tool stamps `clientId` on all existing Search documents and Table rows before the enforcement flag flips per environment.                                                                                          | Must |
| FR-17 | **[Scope-pending]** Optionally reject `metadataFilter` keys not on an allow-list so callers cannot filter on internal fields (e.g. `clientId`). In/out of scope for this iteration is an open question (OQ-2).                                                                                                                                                          | Should |
| FR-18 | **[Open]** Keep the eval harness (`ai-document-system-prompt-harness-eval`) working against a client-scoped index (D6). Approach to be proposed at design stage (OQ-3).                                                                                                                                                                                                 | Should |

## Non-functional requirements
| ID | Category | Requirement | Threshold |
|----|----------|-------------|-----------|
| NFR-1 | Infrastructure | No new Azure resources: single AI Search index, single storage account, single set of tables retained. Isolation is logical only. | Zero new infra resources |
| NFR-2 | Reversibility | Enforcement fully reversible by flipping `CLIENT_FILTERING_ENABLED` off, with no data rewrite required to roll back. | Flag toggle only |
| NFR-3 | Confidentiality | No cross-client existence leakage: responses must not reveal whether another client's resource exists (drives 404-not-403 semantics). | Zero existence leakage |
| NFR-4 | Input validation | `clientId` format validated at the boundary before it is used in OData literals, partition keys, blob paths or metric dimensions. UUID format assumed (D7). Malformed values rejected (401). | 100% of ingress paths |
| NFR-5 | Backward compatibility | Legacy flat-path blobs remain readable; dual-path resolver must not break existing document retrieval. | No regression on legacy blobs |
| NFR-6 | Idempotency | Existing idempotency guarantees (lease TTL, ETag fencing, redelivery skip of terminal rows) preserved under client-aware keying; no duplicate LLM/embedding work or scoring re-enqueue introduced. | No idempotency regression |
| NFR-7 | Contract stability | The internal client-identity header is NOT added to the consumer-facing OpenAPI spec (`api-cp-ai-rag`); the public contract is unchanged (D5). | Consumer spec unchanged |

## Acceptance criteria

### FR-1 / FR-2 — client-identity extraction & 401
- AC-1: Given enforcement is on and a valid client-identity header, when any HTTP endpoint is called, then the extracted `clientId` is available to the handler and the request proceeds (positive path).
- AC-2: Given enforcement is on and the client-identity header is absent, when any HTTP endpoint is called, then the response is **401** and no downstream processing occurs (header absence 401).
- AC-3: Given a caller attempts to override/spoof the identity via a request body or `metadataFilter`, when the request is processed, then only the APIM-injected header value is used; the spoofed value has no effect (header spoof override).

### FR-3 — feature flag
- AC-4: Given `CLIENT_FILTERING_ENABLED=false`, when a request arrives without identity, then it is processed as before (identity optional, no `clientId` filter clause).

### FR-4 / FR-5 — index field & filter injection
- AC-5: Unit test on `generateFilterExpression`: given a `clientId` and any `metadataFilter`, then the produced OData string begins with a non-optional `clientId eq '<escaped>'` clause ANDed with the rest (filter unit test).
- AC-6: Regression test: a client's query never returns chunks stamped with a different `clientId` (filter injection regression).

### FR-6 / FR-7 / FR-8 — partitioning, idempotency, per-client documentId
- AC-7: Given two clients ingest the same `documentId`, then both rows coexist under distinct partition keys and neither dedup, overwrite nor chunk-deletion of one affects the other.
- AC-8: Given a duplicate queue delivery for an already-terminal `(clientId, documentId)`/`(clientId, transactionId)` row, then the worker skips the expensive pipeline (no LLM/embedding call, no scoring re-enqueue) — idempotency preserved under client-aware keys.

### FR-9 / FR-10 — blob prefixing & dual-path
- AC-9: Given enforcement is on, when a document is uploaded, then its blob is written under `c={clientId}/…` and answer-payload blobs use the same prefix.
- AC-10: Given a legacy flat-path blob, when it is triggered/resolved, then the resolver parses it correctly and ownership is taken from the Table row.

### FR-11 / FR-12 — queue & scoring threading
- AC-11: Given an async answer request, when it rides queue redelivery up to `maxDequeueCount`, then `clientId` is present on every redelivery and the lease is claimed on the correct client-scoped row.
- AC-12: Given a scoring message, when the scorer runs, then it reads `clientId` from the `ScoringPayload` (not the filename-only queue message) and writes the groundedness score back to the `(clientId, transactionId)` row.

### FR-13 — telemetry
- AC-13: Given scores from two clients, when telemetry is published, then metrics carry a `client_id` dimension and can be segmented per client (telemetry segmentation).

### FR-14 — cross-client 404
- AC-14: Given client B requests a `documentReference`/`transactionId` owned by client A, then the response is **404** (not 403, not 200), leaking no existence signal (cross-client 404).

### FR-15 / FR-16 — migration & cut-over
- AC-15: Given the backfill tool runs on the live index, then all pre-existing chunks and Table rows are stamped with the incumbent `clientId`, the `clientId eq null` count reaches 0, and the tool is resumable and skips rows under live leases.
- AC-16: Given queues are drained before cut-over, then no in-flight message exists at flag flip and no intermediate-state/dual-write record is produced.

## Constraints
- **Contract-first (D5, CLAUDE.md):** the internal client-identity header is an APIM↔function-app contract only and must not appear in the consumer OpenAPI spec (`api-cp-ai-rag`). Any consumer-facing shape change must go through the spec repo first.
- **Managed identity only** (`.claude/context/azure-functions.md`): no new connection strings/keys; SDK clients use `DefaultAzureCredential`.
- **No new infra** (NFR-1): logical isolation, single index/account/tables.
- **Count-variable invariants** and existing retrieval-refinement pipeline behaviour must be preserved when the filter clause is added.
- **AMP standards:** upstream consumer-identity design is owned by another team and not yet concrete (D4).

## Out of scope
- APIM policy implementation in `cpp-azure-api-management` (coordination dependency only).
- Per-client rate limits / quotas / analytics (AMP standards, later).
- Physical migration of legacy blobs and retirement of the dual-path resolver.
- Per-client index/silo migration runbook (logical isolation now; namespace kept so a client can later be routed to its own index/silo via config).
- The `query_type` PII telemetry fix (adjacent but independent).

## Assumptions
- APIM (per AMP standards) identifies the consumer upstream and passes a trusted `clientId` to the function app in a recognised internal header; backends trust it because they only accept APIM traffic (function key + network restriction) (D4).
- `clientId` is a **UUID** for now (D7), pending confirmation (OQ-1).
- The AI Search chunk `id` is a random UUID (`DocumentChunkingService`), so there is no cross-client key-collision risk in the index.
- Queues can be fully drained at cut-over, so no dual-write/intermediate-state handling is required (D3).
- The stale Confluence claims are disregarded: the OData single-quote escaping already exists; the `DocumentStatusCheckFunction` / `GET /document-status` endpoint does not exist (only `DocumentStatusByReferenceFunction`).

## Dependencies
- **`cpp-azure-api-management`** — APIM policy work to identify the consumer and inject the internal header. This repo defines the internal header contract and enforces behind the D4 abstraction; delivery of enforcement in production depends on that policy landing.
- **`api-cp-ai-rag`** (spec repo) — must confirm the internal header is NOT added to the consumer spec (D5). Impact assessment needed only if any consumer-facing behaviour changes (none currently planned).
- **Migration/backfill tooling** must precede the per-environment flag flip.

## Open questions
1. **OQ-1 — `clientId` final format.** UUID is assumed (D7); confirm before boundary validation is finalised, as the value lands in OData literals, partition keys, blob paths and metric dimensions. — Owner: Mahesh — Due: before design sign-off.
2. **OQ-2 — `metadataFilter` allow-list.** Is rejecting non-allow-listed `metadataFilter` keys in scope for this iteration (FR-17)? — Owner: TBD — Due: design stage.
3. **OQ-3 — eval-harness approach (D6).** Simplest approach to keep `ai-document-system-prompt-harness-eval` working against a client-scoped index (inject an incumbent `clientId`? bypass the filter? dedicated eval client?). — Owner: TBD — Due: design stage.
4. **OQ-4 — incumbent client ID value.** What concrete `clientId` value is assigned to the existing production corpus during migration? — Owner: Mahesh/platform — Due: before backfill run.
