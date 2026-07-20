# User Stories: Multi-Client Data Isolation (CP AI RAG Service)

> Stage 3 artefact (story-writer). Source: `01-requirements.md` (approved), `02-design.md` (approved), `00-input-brief.md`.

## Jira mapping

Parent ticket: [DD-42722](https://tools.hmcts.net/jira/browse/DD-42722) — "Implement multi tenant data structure and storage". Each story below is a lightweight Jira sub-task (user story + description + pointer back to this file); this document remains the source of truth for acceptance criteria, DoD and dependencies. In Jira text, story IDs are written hyphen-free (`MTDI01`) to avoid Jira auto-linking pseudo issue keys.

| Story | Jira sub-task |
|---|---|
| MTDI-01 | [DD-42976](https://tools.hmcts.net/jira/browse/DD-42976) |
| MTDI-02 | [DD-42977](https://tools.hmcts.net/jira/browse/DD-42977) |
| MTDI-03 | [DD-42978](https://tools.hmcts.net/jira/browse/DD-42978) |
| MTDI-04 | [DD-42979](https://tools.hmcts.net/jira/browse/DD-42979) |
| MTDI-05 | [DD-42980](https://tools.hmcts.net/jira/browse/DD-42980) |
| MTDI-06 | [DD-42981](https://tools.hmcts.net/jira/browse/DD-42981) |
| MTDI-07 | [DD-42982](https://tools.hmcts.net/jira/browse/DD-42982) |
| MTDI-08 | [DD-42983](https://tools.hmcts.net/jira/browse/DD-42983) |
| MTDI-09 | [DD-42984](https://tools.hmcts.net/jira/browse/DD-42984) |

**Cross-cutting rule for every dev story (S1–S8):** each must be independently mergeable to `main` with `CLIENT_FILTERING_ENABLED` off/unset, producing **byte-for-byte identical behaviour** to today (NFR-2, AC-4). Enforcement only activates at Phase 4 cut-over, once the flag is flipped **and** the environment is repointed at migrated tables/index alias. The one deliberate exception is MTDI-04's reserved-key rejection, which is **always-on** (not flag-gated) per stakeholder decision 2026-07-20 — see MTDI-04's notes.

**ADR gate:** this initiative requires an ADR before implementation begins — *"Logical multi-client isolation via a single `clientId` namespace (Search field + Table partition key + blob prefix + queue field) behind `CLIENT_FILTERING_ENABLED`"*, capturing design decisions DD-1…DD-13 and the identified one-way doors (partition-key choice; alias-on-v2). Store it under `docs/pipeline/adrs/` and get tech-lead sign-off **before MTDI-01 starts** — it is a merge gate for the whole story set.

---

## Summary table

| Story ID | Title | Phase | Size | Dependencies | Can run in parallel with |
|---|---|---|---|---|---|
| MTDI-01 | Shared client-identity abstraction + feature-flag env vars | Phase 0 | M | None (ADR must be accepted first) | MTDI-02 |
| MTDI-02 | Additive client-scoping fields across models, schema and queue payloads | Phase 0 | M | None (ADR must be accepted first) | MTDI-01 |
| MTDI-03 | Client-aware idempotency store/guard + Table Storage re-keying | Phase 1 | L | MTDI-01, MTDI-02 | MTDI-04, MTDI-05, MTDI-07 |
| MTDI-04 | Search filter clause, retrieval threading & reserved-metadata-key rejection | Phase 1 | M | MTDI-01, MTDI-02 | MTDI-03, MTDI-05, MTDI-07 |
| MTDI-05 | Blob path prefixing with dual-path (legacy) resolution | Phase 1 | M | MTDI-02 | MTDI-03, MTDI-04, MTDI-07 |
| MTDI-06 | Wire the five HTTP functions, two queue workers and scorer to client identity | Phase 2 | L | MTDI-01…05 | — (integration point; sequential) |
| MTDI-07 | Migration-tool `clientIdOverride` + documentation updates | Phase 3 | S | MTDI-02 | MTDI-03, MTDI-04, MTDI-05, MTDI-06 |
| MTDI-08 | Eval harness client scoping + integration-test suite additions | Phase 3 | M | MTDI-06 (and MTDI-04 for the filter-injection regression) | MTDI-07 |
| MTDI-09 | **[OPS — not a code story]** Per-environment cut-over runbook execution | Phase 4 | S (ops) | MTDI-06, MTDI-07, MTDI-08 all merged & deployed | — (sequential, per environment) |

**Estimation legend:** S = fits comfortably in a few days within one sprint; M = most of a sprint for one engineer/pair; L = a full sprint, or split across two engineers working sub-slices in the same story (kept as one story because the change is not independently shippable in smaller pieces without breaking a single interface's contract — e.g. `IdempotencyStatusStore`'s signature).

**Parallelisation:** MTDI-01 and MTDI-02 (Phase 0) have no interdependency and should be started together. Once both land, MTDI-03, MTDI-04, MTDI-05 (Phase 1) and MTDI-07 (Phase 3, different module — `ai-document-migration-tool`) can all proceed in parallel, since they touch disjoint modules/classes. MTDI-06 is the integration point and must wait for MTDI-01–05 to land (it is intentionally the widest-fan-in story). MTDI-08 needs MTDI-06 merged (it exercises the full flag-on path end to end) but can start test-scaffolding work (fixtures, harness `.env` wiring) as soon as MTDI-04 lands. MTDI-09 is strictly sequential and last, run once per environment by the release operator.

---

## MTDI-01: Shared client-identity abstraction + feature-flag env vars

### User story
As a **platform engineer maintaining the CP AI RAG Service**,
I want **a single, shared client-identity resolution abstraction in `ai-document-shared-artefacts`, gated behind a `CLIENT_FILTERING_ENABLED` flag**,
so that **every HTTP function can later adopt one consistent, testable way to extract and validate the caller's `clientId`, with a single point of change when APIM's identity mechanism (AMP, D4) is finalised**.

### Background
FR-1/FR-2/FR-3 (D4, D5, D7). This is foundational plumbing only — **no HTTP function is wired to it yet** (that's MTDI-06). It must be safe to merge in isolation: it introduces new classes and new, unused-by-default env vars, with zero behavioural change to any existing endpoint.

### Acceptance criteria
- [ ] AC-001 (delivers AC-1, unit level): Given `CLIENT_FILTERING_ENABLED=true` and a request carrying a valid UUID in the `CLIENT_IDENTITY_HEADER` header, when `HeaderClientIdentityResolver.resolve(request)` is called, then it returns `ClientContext.of(clientId)` with `enforced()==true` and `clientId()` populated.
- [ ] AC-002 (delivers AC-2, unit level): Given `CLIENT_FILTERING_ENABLED=true` and the header is absent, empty, or not a valid UUID, when `resolve(request)` is called, then it throws `ClientIdentityException` (mapped by callers to 401 — the mapping itself is verified in MTDI-06).
- [ ] AC-003 (delivers AC-3, unit level): Given a request whose body or `metadataFilter` contains a different clientId-like value, when `resolve(request)` is called, then only the header value is used; body/`metadataFilter` content has no effect on the resolved `ClientContext`.
- [ ] AC-004 (delivers AC-4, unit level): Given `CLIENT_FILTERING_ENABLED=false` (or unset — default), when `resolve(request)` is called regardless of header presence, then it returns `ClientContext.unenforced()` with `clientId()` empty and `enforced()==false` — no exception is ever thrown in this mode.
- [ ] AC-005: Given a malformed but non-empty header value (not a valid UUID format per NFR-4/D7), when enforcement is on, then `ClientIdentityException` is thrown (same as AC-002) — validated via `UuidUtil.isValid` reuse, not a new validation routine.
- [ ] AC-006: Given `CLIENT_IDENTITY_HEADER` is unset, when the resolver is constructed, then it defaults to `X-Client-Id` (case-insensitive lookup, since the Functions host lower-cases header keys).
- [ ] AC-007: Given the worker-side helper `ClientId.requireValid(String)`, when called with a null/blank/non-UUID value, then it throws the same validation exception type used at the HTTP boundary, so queue workers (wired in MTDI-06) can defensively re-validate a payload-carried `clientId` with one shared routine.

### NFR links
- NFR-2 (Reversibility): flag defaults to `false`; toggling it back off requires no data rewrite.
- NFR-4 (Input validation): UUID-shape validation happens once, at this single point, before `clientId` can reach any OData literal, partition key, blob path or metric dimension downstream.
- NFR-7 (Contract stability): the header and flag are internal-only; nothing here touches `api-cp-ai-rag`.

### Out of scope for this story
- Wiring `ClientIdentityResolver`/`ClientContext` into any of the five HTTP functions (MTDI-06).
- Mapping `ClientIdentityException` → HTTP 401 response body/status (MTDI-06, `HttpResponses.unauthorized`).
- Any change to `AzureAISearchService`, Table services, blob naming, or queue payloads (MTDI-02/03/04/05).

### Definition of done
- [ ] Code reviewed and approved (≥1 human approval per branch policy; no direct commits to `main`).
- [ ] New package `uk.gov.moj.cp.ai.client.identity` (`ClientIdentityResolver`, `HeaderClientIdentityResolver`, `ClientContext`, `ClientIdentityException`, `ClientId`) added to `ai-document-shared-artefacts` with unit tests covering all ACs above.
- [ ] `SharedSystemVariables` gains `CLIENT_FILTERING_ENABLED` (default `false`) and `CLIENT_IDENTITY_HEADER` (default `X-Client-Id`); documented in root `CLAUDE.md`'s environment-variable section.
- [ ] `mvn test -pl ai-document-shared-artefacts` passes; `mvn verify` shows JaCoCo coverage ≥80% on all new classes.
- [ ] No integration-test changes required for this story (nothing is wired yet) — confirmed by running the existing `ai-service-orchestration-test` suite unchanged and green.
- [ ] SonarQube quality gate passes on the Azure Pipelines PR build.
- [ ] Verified with a manual/unit check that `CLIENT_FILTERING_ENABLED=false` (or absent) never throws, matching current behaviour of every existing caller.

### Notes / open questions
- ADR gate applies (see initiative header) — must be accepted before this story's PR merges.
- OQ-1 (`clientId` format — UUID assumed, D7) should be confirmed by the time this story starts; if it changes, only `ClientId`/`UuidUtil` usage here needs revisiting (that's the point of the single-abstraction design, DD-2).
- `HttpResponses.unauthorized(request)` helper is a natural companion but is deferred to MTDI-06 since no caller needs it yet — flag if reviewers want it stubbed here instead.

---

## MTDI-02: Additive client-scoping fields across models, schema and queue payloads

### User story
As a **platform engineer preparing the data plane for client isolation**,
I want **`clientId` added as an additive, nullable field to every model, DTO, table column and the pending v2 Search index schema that will eventually carry it**,
so that **later stories (idempotency re-keying, search filtering, blob prefixing, worker wiring) have a stable, backward-compatible shape to build against, with zero behavioural change today**.

### Background
FR-4 (index field), FR-6 (`TC_CLIENT_ID` column), FR-11/FR-12 (queue/scoring payload fields), DD-4 (fold `clientId` into the single pending v2 index rebuild rather than a v3). All changes here are additive fields on existing records/JSON — no reader depends on the field being present yet, and `@JsonIgnoreProperties(ignoreUnknown = true)` on the queue DTOs already makes this safe for any message drained mid-rollout (though D3 means none are in flight at cut-over anyway).

### Acceptance criteria
- [ ] AC-008: Given `IndexConstants`, when this story lands, then `CLIENT_ID = "clientId"` is defined and used by no production code path yet (verified by a compile-time reference check / grep in review — actual usage lands in MTDI-04).
- [ ] AC-009: Given `ChunkedEntry`, when a chunk is built without a `clientId` (today's callers), then serialisation/deserialisation is unchanged and `clientId` defaults to `null`; when built with `.clientId("...")`, then the value round-trips through JSON.
- [ ] AC-010: Given `vector-db-index-schema-v2.json`, when the schema is loaded, then it declares a `clientId` field (`filterable: true, searchable: false, retrievable: true, stored: true, sortable: false, facetable: false`) matching `documentId`'s v2 attribute shape.
- [ ] AC-011: Given `QueueIngestionMetadata` and `AnswerGenerationQueuePayload`, when a message is deserialised without a `clientId` property (legacy/pre-rollout shape), then deserialisation succeeds with `clientId == null`.
- [ ] AC-012: Given `ScoringPayload`, when built by the existing producers unchanged in this story, then the payload still serialises correctly with `clientId` simply absent/null (producers are updated to set it in MTDI-06).
- [ ] AC-013: Given `entity/StorageTableColumns`, when this story lands, then `TC_CLIENT_ID` is defined as a constant, unused by any table read/write path yet (usage lands in MTDI-03).

### NFR links
- NFR-1 (No new infra): this is a field addition to the existing single index/tables, not a new resource.
- NFR-5 (Backward compatibility): every change here is additive and nullable; nothing currently reading these models/messages/schema breaks.

### Out of scope for this story
- Any code that *reads* `clientId` from these fields to change behaviour (filtering, keying, prefixing) — MTDI-03/04/05/06.
- Running the actual v2 index rebuild/backfill against a live environment (MTDI-07 tool, MTDI-09 runbook).
- `AzureAISearchService.getColumnsToRetrieve()` change to include `CLIENT_ID` (bundled into MTDI-04, part of the search-service read path).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All listed model/DTO/schema/constant changes made in `ai-document-shared-artefacts` (`IndexConstants`, `ChunkedEntry`, `QueueIngestionMetadata`, `ScoringPayload`, `StorageTableColumns`, `vector-db-index-schema-v2.json`).
- [ ] Unit tests added for each additive field's serialise/deserialise-without-field backward-compatibility case (AC-009, AC-011, AC-012).
- [ ] `mvn test -pl ai-document-shared-artefacts` passes; `mvn verify` JaCoCo coverage ≥80% on new/changed lines.
- [ ] Existing `ai-service-orchestration-test` suite re-run unchanged and green.
- [ ] SonarQube quality gate passes on the Azure Pipelines PR build.
- [ ] `SCHEMA_CHANGES.md` updated documenting the `clientId` field addition and its place in the pending v2 rebuild (DD-4), including the fallback path (in-place add via management API) for any environment already cut over to v2.

### Notes / open questions
- Confirm per-environment whether `AZURE_SEARCH_SERVICE_INDEX_NAME` already resolves to a v2 alias before MTDI-07/09 run (design Risk #3) — this story only prepares the schema.
- `AnswerGenerationQueuePayload.clientId` added last in field order for consistency with `QueueIngestionMetadata`.

---

## MTDI-03: Client-aware idempotency store/guard + Table Storage re-keying

### User story
As a **platform engineer responsible for exactly-once processing guarantees**,
I want **`IdempotencyStatusStore`, `IdempotencyGuard`, `ClaimToken` and both Table services made client-aware, keyed on `(clientId, key)` with a null-clientId legacy fallback**,
so that **two clients can safely reuse the same `documentId`/`transactionId` without collision, while every existing idempotency guarantee (lease TTL, ETag fencing, terminal-skip, redelivery safety) is preserved bit-for-bit when `clientId` is absent**.

### Background
FR-6, FR-7, FR-8, NFR-6, DD-5, DD-6. This is the highest-risk story in the initiative (design Risk #1: "broad, mechanical fan-out... easy to miss a call site and break fencing"). Depends on MTDI-01 (`ClientId.requireValid`) and MTDI-02 (`TC_CLIENT_ID` column). The interface signature change (`runOnce(clientId, key, work)`, `ClaimToken(clientId, key, etag)`) is not shippable in smaller independent pieces without leaving the codebase in a broken intermediate state, hence the L size.

### Acceptance criteria
- [ ] AC-014 (delivers AC-7): Given two clients ingest the same `documentId` (via direct table-service calls in a test harness), when `isDocumentAlreadyProcessed(clientId, documentId)` / `getDocumentById(clientId, documentId)` are called for each, then both rows coexist under distinct partition keys and dedup, overwrite (`supersededDocuments`) and chunk-deletion scoping for one client never touches the other's row.
- [ ] AC-015 (delivers AC-8): Given a duplicate `runOnce(clientId, key, work)` call against an already-terminal `(clientId, key)` row, when the guard evaluates the row, then `work` is never invoked (no LLM/embedding call, no scoring re-enqueue) — verified for both a real `clientId` and the legacy null-clientId case.
- [ ] AC-016: Given a null or blank `clientId` passed to any `IdempotencyStatusStore`/table-service method, when the effective partition key is computed, then it falls back to `partitionKey = key` (today's PK==RK==key behaviour), so pre-rollout rows and flag-off callers resolve unchanged.
- [ ] AC-017: Given a live lease is claimed on `(clientId, key)` and a second delivery arrives for the same client+key, when `claimLease`/`readForClaim` run, then ETag fencing behaves identically to today (412 → `EtagMismatchException` on a reclaimed/expired lease; live-lease rethrow-vs-WARN-at-exhaustion unchanged) — re-verified per client partition.
- [ ] AC-018: Given the lease-TTL invariant `IDEMPOTENCY_LEASE_TTL_SECONDS < visibilityTimeout × (maxDequeueCount − 1)`, when re-keying is applied, then the invariant and its enforcement are unaffected by the two-part key.

### NFR links
- NFR-6 (Idempotency): the central concern of this story.
- NFR-2 (Reversibility): the null-clientId fallback is what makes flag-off behaviour identical to pre-change.
- NFR-3 (Confidentiality, partial): partition scoping here is the mechanism AC-14 (cross-client 404) later relies on in MTDI-06.

### Out of scope for this story
- New physical tables / `TableCopier` execution against a live environment (MTDI-07/MTDI-09) — this story only implements the *code* that reads/writes the new keying shape.
- Wiring worker/function call sites to pass a real, HTTP-resolved `clientId` (MTDI-06).
- 404 semantics at the HTTP function layer (MTDI-06) — this story only ensures the *store* returns not-found for a cross-partition lookup.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `IdempotencyStatusStore`, `IdempotencyGuard`, `ClaimToken`, `DocumentIngestionOutcomeTableService`, `AnswerGenerationTableService`, `DocumentUploadService.isDocumentAlreadyProcessed` all updated to the `(clientId, key)` signature with null-clientId fallback.
- [ ] Exhaustive unit coverage on both store implementations and the guard, including the AC-8 regression test explicitly named as such, and a dedicated legacy-fallback test suite (AC-016).
- [ ] `mvn test -pl ai-document-shared-artefacts,ai-document-ingestion-function,ai-document-answer-retrieval-function,ai-document-metadata-check-function` passes.
- [ ] `mvn verify` JaCoCo coverage ≥80% on all changed classes (highest regression risk in the initiative — do not under-cover it).
- [ ] Existing `ai-service-orchestration-test` suite re-run unchanged and green.
- [ ] SonarQube quality gate passes on the Azure Pipelines PR build.
- [ ] Explicit sign-off note in the PR description confirming every existing call site of the old `(key)`-only signatures has been migrated (no leftover compile shims) — reviewer must grep for old signatures before approving, per Risk #1.

### Notes / open questions
- This story alone does not achieve end-to-end AC-7/AC-8 behaviour — it proves the store/guard logic in isolation; MTDI-06 completes the loop.
- Consider a two-pass review: (1) interface + `IdempotencyGuard`/`ClaimToken`, (2) both Table services — but ship as one story.

---

## MTDI-04: Search filter clause, retrieval threading & reserved-metadata-key rejection

### User story
As a **consuming client application querying the RAG service**,
I want **every search query to be scoped by a non-optional, correctly-escaped `clientId` filter clause when enforcement is on, and to be unable to weaken that scoping via `metadataFilter`**,
so that **I never receive chunks belonging to another client, and cannot accidentally or deliberately widen my own result set to another client's data**.

### Background
FR-4, FR-5, FR-8 (supersede-scoping side), OQ-2/DD-12. Depends on MTDI-01 (flag var) and MTDI-02 (`IndexConstants.CLIENT_ID`, `ChunkedEntry.clientId`, schema v2 field). The count-variable invariant (`SEARCH_NEAREST_NEIGHBOURS_COUNT ≥ SEARCH_TOP_RESULTS_COUNT > SEARCH_MMR_FINAL_COUNT`) and the containment/dedup/MMR pipeline order are unaffected — the `clientId` clause only narrows the candidate set exactly as a `metadataFilter` does today.

### Acceptance criteria
- [ ] AC-019 (delivers AC-5): Given a `clientId` and any `metadataFilter` list, when `AzureAISearchService.generateFilterExpression(clientId, metadataFilters)` is called with enforcement on, then the produced OData string begins with a non-optional `clientId eq '<escaped>'` clause ANDed with the rest, using the existing `StringUtil.escapeODataStringLiteral` (no new escaping, DD-3).
- [ ] AC-020: Given `clientId` is empty/null (flag off), when `generateFilterExpression` is called, then the produced expression is byte-for-byte identical to today's output — the explicit AC-4 regression check at the search-service layer.
- [ ] AC-021 (delivers AC-6): Given two clients' chunks exist in the same index, when a query with client A's `clientId` runs through `search(clientId, ...)`, then no chunk stamped with a different `clientId` is ever returned, regardless of `metadataFilter` content (component-level; end-to-end version in MTDI-08).
- [ ] AC-022: Given `getColumnsToRetrieve()`, when a search executes, then `IndexConstants.CLIENT_ID` is included, so `clientId` round-trips into `ChunkedEntry` for downstream consumers.
- [ ] AC-023: Given ingestion's supersede/deletion path (`DocumentStorageService.getSearchResults`), when it filters existing chunks for a document being overwritten, then the filter is scoped by `clientId eq '<clientId>' and (...)` so client A can never mark client B's chunks inactive.
- [ ] AC-024: Given a `metadataFilter` entry with `key` in the reserved set (`clientId`, `is_active`), when the answer-retrieval request is validated, then the request is rejected with `400` before any search call is made — **regardless of `CLIENT_FILTERING_ENABLED`** (always-on, stakeholder decision 2026-07-20).
- [ ] AC-025: Given `CLIENT_FILTERING_ENABLED=false` and a `metadataFilter` containing only non-reserved keys, when the request is validated, then it is accepted and processed exactly as today — the reserved-key rejection is the **single deliberate flag-off behaviour change** in this initiative (see Notes).

### NFR links
- NFR-4 (Input validation): all `clientId` values reaching an OData literal are escaped via the existing routine.
- NFR-2 (Reversibility): AC-020 is the explicit regression guarantee.

### Out of scope for this story
- Threading a real, HTTP-resolved `clientId` into function call sites (MTDI-06).
- The general `metadataFilter` allow-list (FR-17/OQ-2 full scope) — explicitly deferred; only the minimal reserved-key rejection (DD-12) is in scope.
- End-to-end integration regression test across real HTTP calls (MTDI-08).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `AzureAISearchService.generateFilterExpression`/`search`/`getColumnsToRetrieve` signatures updated; `DocumentStorageService.getSearchResults` supersede-scoping updated; a small `MetadataFilterValidator` (or equivalent) added for reserved-key rejection.
- [ ] Unit tests cover AC-019 through AC-025, including an explicit flag-off byte-for-byte regression test (AC-020).
- [ ] `mvn test -pl ai-document-answer-retrieval-function,ai-document-ingestion-function` passes; `mvn verify` JaCoCo coverage ≥80% on changed classes.
- [ ] Existing `ai-service-orchestration-test` suite re-run unchanged and green.
- [ ] SonarQube quality gate passes on the Azure Pipelines PR build.
- [ ] Confirmed the count-variable invariant documentation in `CLAUDE.md` still holds unchanged (call out explicitly in the PR description).

### Notes / open questions
- **Resolved (2026-07-20):** the reserved-key rejection is **always-on**, not gated by `CLIENT_FILTERING_ENABLED` — stakeholder decision at story sign-off. This is the initiative's single deliberate flag-off behaviour change: a request whose `metadataFilter` uses the internal keys `clientId`/`is_active` gets 400 even before cut-over. No legitimate caller uses these keys today; the change is defence-in-depth and requires no consumer contract change (`metadataFilter` keys are free-form strings in the spec).
- The general `metadataFilter` allow-list (OQ-2 full scope) remains an open follow-up; not resolved by this story.

---

## MTDI-05: Blob path prefixing with dual-path (legacy) resolution

### User story
As a **platform engineer preparing storage for client-namespaced blobs**,
I want **`DocumentBlobNameResolver` and the blob trigger to support both a new `c={clientId}/…` prefixed shape and the existing legacy flat shape**,
so that **new client-scoped uploads can be namespaced without breaking retrieval of any already-ingested legacy document**.

### Background
FR-9, FR-10, NFR-5, DD-8. Depends on MTDI-02 (`QueueIngestionMetadata.clientId` to carry the parsed value onward). The Table row remains the authoritative ownership record — the path is a convenience, never a security boundary.

### Acceptance criteria
- [ ] AC-026 (delivers AC-9, structural): Given a non-empty `clientId`, when `DocumentBlobNameResolver.getBlobName(clientId, documentId, ext)` is called, then it returns `c={clientId}/{documentId}_{yyyyMMdd}.{ext}`.
- [ ] AC-027: Given a null/empty `clientId` (flag off or legacy caller), when `getBlobName` is called, then it returns the unchanged flat `{documentId}_{yyyyMMdd}.{ext}` shape — byte-for-byte identical to today.
- [ ] AC-028 (delivers AC-10): Given a legacy flat-path blob name, when `getDocumentId(blobName)` is called, then it parses correctly via the existing, untouched `BLOB_PATTERN` regex.
- [ ] AC-029 (delivers AC-10): Given a prefixed blob name, when `getDocumentId(blobName)` and the new `getClientId(blobName)` are called, then they correctly extract `documentId` and `clientId` via a distinct, additive regex branch that does not alter the legacy branch's behaviour.
- [ ] AC-030: Given a legacy flat blob name, when `getClientId(blobName)` is called, then it returns `null` — signalling "treat as legacy-owned, defer to the Table row for ownership."
- [ ] AC-031: Given `DocumentBlobTriggerFunction` processing a triggered blob, when it parses the blob name, then it extracts `clientId` from the prefix if present (else `null`), looks up the Table row by the resolved `(clientId, documentId)` pair (using MTDI-03's client-aware table service), and sets `clientId` on the outgoing `QueueIngestionMetadata`.

### NFR links
- NFR-5 (Backward compatibility): the entire point of this story — verified by AC-027/AC-028/AC-030.
- NFR-1 (No new infra): same container, a virtual `c=` directory prefix only.

### Out of scope for this story
- `DocumentUploadFunction` actually calling `getBlobName` with a real, HTTP-resolved `clientId` (MTDI-06).
- `ChunkUtil` filename methods' invocation with a real `clientId` (MTDI-06) — the parameterised methods themselves are in scope here.
- Physical migration of legacy blobs or retirement of the dual-path resolver (out of scope for the whole initiative).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `DocumentBlobNameResolver` gains prefixed `getBlobName`, dual-path `getDocumentId`, and new `getClientId`; `ChunkUtil` filename methods gain a `clientId` parameter with the same null-safe fallback; `DocumentBlobTriggerFunction` updated to parse-and-thread `clientId`.
- [ ] Unit tests cover both blob-name shapes for every resolver method.
- [ ] `mvn test -pl ai-document-metadata-check-function,ai-document-answer-retrieval-function` passes; `mvn verify` JaCoCo coverage ≥80% on changed classes.
- [ ] Existing `ai-service-orchestration-test` suite re-run unchanged and green.
- [ ] SonarQube quality gate passes on the Azure Pipelines PR build.
- [ ] `BlobClientService.getSasUrl`/`getBlobClient` verified to handle `/`-containing blob names — confirm rather than assume.

### Notes / open questions
- None outstanding; low-risk, purely additive/parsing logic.

---

## MTDI-06: Wire the five HTTP functions, two queue workers and scorer to client identity

### User story
As a **consuming client application**,
I want **the five HTTP endpoints to require and enforce my client identity (when enforcement is on), the async/queue workers and scorer to carry that identity through every retry and redelivery, and cross-client lookups to return 404**,
so that **my documents, answers and scores are isolated from every other client's, with no existence leakage, and my in-flight requests survive redelivery correctly**.

### Background
FR-2, FR-7 (call-site half), FR-11, FR-12, FR-13, FR-14. The integration story: first point where a *real, HTTP-resolved* `clientId` flows through the abstractions built in MTDI-01…05. Intentionally the widest-fan-in story; cannot start until all five precede it.

### Acceptance criteria
- [ ] AC-032 (delivers AC-1, integration): Given enforcement on and a valid `X-Client-Id` header, when any of the five HTTP endpoints is called, then the resolved `clientId` is threaded into the corresponding service call and the request succeeds.
- [ ] AC-033 (delivers AC-2, integration): Given enforcement on and the header absent/invalid, when any of the five HTTP endpoints is called, then the response is `401` via a shared `HttpResponses.unauthorized(request)` helper, and no downstream processing (no table write, no queue enqueue, no search call) occurs.
- [ ] AC-034 (delivers AC-3, integration): Given a request body or `metadataFilter` carrying a spoofed clientId-like value, when processed, then only the header-derived value is used end to end (verified via a test asserting the persisted/queried `clientId` matches the header, not the body).
- [ ] AC-035 (delivers AC-14): Given client B calls `GET /document-upload/{documentReference}` or `GET /answer-user-query-async-status/{transactionId}` for a resource owned by client A, when the lookup runs against `(clientB, id)`, then the response is `404` — never `403`, never `200` with another client's data.
- [ ] AC-036 (delivers AC-11): Given an async answer request rides queue redelivery up to `maxDequeueCount` (3), when each redelivery attempt runs, then `clientId` is present on every attempt, and `AnswerGenerationFunction.runOnce(payload.clientId(), transactionId, ...)` claims the lease on the correct client-scoped row each time, including citation-guard retries.
- [ ] AC-037 (delivers AC-12): Given an answer is generated (sync or async), when the `ScoringPayload` blob is written, then it carries `clientId`; when `AnswerScoringFunction` reads it, then it writes the groundedness score back via `recordGroundednessScore(scoringPayload.clientId(), transactionId, score)` — verified for both sync (`transactionId=null`, no table write, unchanged) and async paths.
- [ ] AC-038 (delivers AC-13): Given scores from two different clients, when `PublishScoreService.publishGroundednessScore(score, userQuery, clientId)` runs, then the published Azure Monitor histogram carries both `query_type` and a new `client_id` attribute, independently queryable per client.
- [ ] AC-039: Given `CLIENT_FILTERING_ENABLED=false` (or unset), when any of the five endpoints, either worker, or the scorer runs, then behaviour is identical to pre-change: no 401, no filter clause, PK==RK==key, flat blob paths, no `client_id` telemetry dimension (the master flag-off regression check for the whole story).
- [ ] AC-040: Given `DocumentIngestionFunction`'s worker path, when a message is processed, then `clientId` is threaded from `QueueIngestionMetadata` through `runOnce`, `DocumentIngestionOrchestrator.recordOutcome`, `DocumentChunkingService.createChunkedEntry` (setting `chunk.clientId`), and `DocumentStorageService.uploadChunks` (writing the `clientId` column).

### NFR links
- NFR-2 (Reversibility): AC-039 is the explicit master regression gate.
- NFR-3 (Confidentiality): AC-035 is the direct test of "no existence leakage".
- NFR-4 (Input validation): every worker call site defensively re-validates via `ClientId.requireValid` (MTDI-01) before use.
- NFR-7 (Contract stability): confirm via the `api-contract-check` skill that no request/response schema in `api-cp-ai-rag` changed.

### Out of scope for this story
- The migration tool and eval harness (MTDI-07/MTDI-08).
- The general `metadataFilter` allow-list beyond MTDI-04's reserved-key rejection.
- Actually flipping `CLIENT_FILTERING_ENABLED=true` in any deployed environment (MTDI-09).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All five HTTP functions adopt the resolver-then-401 pattern; both queue workers and `AnswerScoringFunction` thread `clientId` per the ACs above.
- [ ] Unit tests for every function/worker cover the positive path, the 401 path, the spoof-resistance path, and — for the two GET functions — the cross-client 404 path.
- [ ] `mvn test` (all affected modules) passes; `mvn verify` JaCoCo coverage ≥80% on all changed classes.
- [ ] Integration tests updated/added in `ai-service-orchestration-test` for the enforcement-on paths introduced here (minimal set proving the wiring; the fuller regression matrix lands in MTDI-08) and passing via `./ai-service-orchestration-test/run-integration-test.sh`.
- [ ] Flag-off regression explicitly re-run and green: the full existing `ai-service-orchestration-test` suite passes unchanged with `CLIENT_FILTERING_ENABLED` unset (AC-039).
- [ ] SonarQube quality gate passes on the Azure Pipelines PR build.
- [ ] `Azure/local.settings.sample.json` updated in every touched module with `CLIENT_FILTERING_ENABLED` and `CLIENT_IDENTITY_HEADER` documented (defaults off); root `CLAUDE.md` cross-referenced.
- [ ] `api-contract-check` skill re-run and confirms no `operationId` request/response schema drift in `api-cp-ai-rag` (NFR-7).

### Notes / open questions
- Natural point to also verify the count-variable invariant is unaffected in a real end-to-end run (first story where a real `clientId` reaches `search(...)`).
- Given the size, consider a mid-story checkpoint PR after the two GET functions + 404 semantics land, before tackling the two workers + scorer (higher-risk redelivery/fencing paths).

---

## MTDI-07: Migration-tool `clientIdOverride` + documentation updates

### User story
As a **release/platform operator**,
I want **the existing `ai-document-migration-tool`'s index copier extended with a `clientIdOverride` argument (mirroring the table copier's existing `partitionKeyOverride`)**,
so that **I can stamp the incumbent `clientId` onto the entire existing production corpus (Search documents and Table rows) in one idempotent, resumable pass before enforcement is switched on**.

### Background
FR-15, FR-16, DD-9. Depends only on MTDI-02 (`ChunkedEntry.clientId`, schema v2 field) — this module is otherwise independent of the function modules, so it can be built in parallel with MTDI-03…06.

### Acceptance criteria
- [ ] AC-041 (delivers AC-15, tool-level): Given `IndexMigrationTool` is run with a `clientIdOverride` argument, when `IndexCopier.uploadPage` processes each `ChunkedEntry`, then the copy is stamped with `.clientId(override)` before upload, uploads remain idempotent upserts keyed by `id`, and a re-run is safe.
- [ ] AC-042: Given `clientIdOverride` is blank or `-` (matching the table tool's convention), when the tool runs, then chunks are copied verbatim with no `clientId` stamped (parity with today's copy-only behaviour).
- [ ] AC-043 (delivers AC-15, resumability): Given the tool is interrupted partway and re-run, when it processes the same source index again, then previously-stamped documents are re-stamped identically (idempotent) and the `clientId eq null` count in the target index continues to converge to 0.
- [ ] AC-044: Given `TableCopier` already supports `partitionKeyOverride` and requires **no code change**, when it is run twice (once per table) with `partitionKeyOverride=<incumbent clientId>`, then rows are copied into new client-partitioned tables verbatim on all data columns, including stale lease columns, which copy harmlessly since queues are drained (D3) and no live leases exist at migration time.
- [ ] AC-045: Given the tool's argument parser, when an operator supplies `--help` or omits the new argument, then usage documentation reflects the new `clientIdOverride` option clearly, consistent with the existing documentation style.

### NFR links
- NFR-1 (No new infra): the tool only stamps/copies within the retained resources.
- NFR-2 (Reversibility): the migration only *adds* data; old tables/index remain untouched and available for rollback until decommissioned.

### Out of scope for this story
- Actually running the tool against any live/deployed environment (MTDI-09).
- The alias repoint (`az rest`) or any cut-over scripting (MTDI-09/runbook).
- The eval harness's own `.env` changes (MTDI-08).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `IndexMigrationTool` argument parsing and `IndexCopier`/`uploadPage` updated with `clientIdOverride`; `TableMigrationTool`/`TableCopier` verified as needing **no code change** (confirmed by a targeted unit test, not a re-implementation).
- [ ] Unit tests cover AC-041 through AC-044.
- [ ] `mvn test -pl ai-document-migration-tool` passes; `mvn verify` JaCoCo coverage ≥80% on changed classes.
- [ ] No `ai-service-orchestration-test` change required (tool not exercised by that suite).
- [ ] SonarQube quality gate passes on the Azure Pipelines PR build.
- [ ] README and `SCHEMA_CHANGES.md` updated with the new `clientIdOverride` argument, its semantics, and a worked example matching the runbook's index-copy step.

### Notes / open questions
- OQ-4 (incumbent `clientId` value) must be confirmed before this tool is *run* (MTDI-09) — it does not block *building* it.
- Design Risk #3: confirm per-environment whether the index alias already points at v2; the fallback (in-place field add + stamping pass) is a runbook consideration for MTDI-09, not a code change here.

---

## MTDI-08: Eval harness client scoping + integration-test suite additions

### User story
As a **QA/platform engineer validating the isolation guarantees before cut-over**,
I want **the eval harness updated to run against the client-scoped search path, and the integration-test suite extended with a second-client fixture, cross-client 404 checks, a filter-injection regression, and a spoof-resistance test**,
so that **we have automated, repeatable proof that isolation holds end-to-end before any environment's `CLIENT_FILTERING_ENABLED` flag is flipped**.

### Background
FR-18, OQ-3/DD-13, AC-6 (end-to-end), AC-14 (end-to-end). Depends on MTDI-06 being merged, and on MTDI-04 for the filter-injection regression specifically.

### Acceptance criteria
- [ ] AC-046: Given the harness's `.env` gains `EVAL_CLIENT_ID` and `CLIENT_FILTERING_ENABLED=true`, when the harness runs its embed→search→generate→cite pipeline, then it passes `EVAL_CLIENT_ID` into `search(clientId, ...)`, exercising the real filtered path.
- [ ] AC-047: Given the harness's existing citation/verbosity/coverage metrics, when run against the client-scoped index with `EVAL_CLIENT_ID` set to the incumbent stamped value, then metrics are comparable to pre-change baselines (no unexplained coverage drop attributable to the filter clause).
- [ ] AC-048: Given `ai-service-orchestration-test`'s request helpers, when any test constructs a request, then an `X-Client-Id` header can be supplied via a shared helper, defaulting to a documented test client fixture.
- [ ] AC-049: Given a second-client fixture, when a document is ingested for client A and another for client B with the **same `documentId`**, then both ingest successfully and coexist (integration-level proof of AC-7).
- [ ] AC-050 (delivers AC-14, integration): Given client B polls/queries a `documentReference`/`transactionId` created by client A, when the request completes, then the response is `404`.
- [ ] AC-051 (delivers AC-6, integration): Given both clients' documents are ingested and a query is run scoped to client A with a broad/no `metadataFilter`, when results are returned, then no chunk belonging to client B appears (full end-to-end filter-injection regression).
- [ ] AC-052 (delivers AC-3, integration): Given a request with a spoofed `clientId`-shaped value inside `metadataFilter` or the request body, when processed, then the response reflects only the header-derived identity, and the spoofed value has zero effect on returned data.

### NFR links
- NFR-3 (Confidentiality): AC-050/AC-051 are the acceptance-level proof of no existence/data leakage.
- NFR-5 (Backward compatibility): harness changes must not break existing single-client eval runs with the flag off.
- NFR-6 (Idempotency): the second-client duplicate-documentId fixture is a natural place to assert AC-8 end-to-end if a redelivery is simulated.

### Out of scope for this story
- Any change to the harness's core evaluation logic beyond threading `clientId` into the search call.
- Running the harness or integration suite against a cut-over production environment (MTDI-09 validation).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `ai-document-system-prompt-harness-eval/.env.sample` updated with `EVAL_CLIENT_ID` and `CLIENT_FILTERING_ENABLED`; harness search invocation updated.
- [ ] `ai-service-orchestration-test` request helpers updated with header injection; second-client fixture added; new tests for cross-client 404, filter-injection regression, and spoof resistance added.
- [ ] `mvn test` passes for touched modules; harness changes verified via its own `run-harness.sh` (documented run — the harness is offline/on-demand).
- [ ] Full integration-test run via `./ai-service-orchestration-test/run-integration-test.sh` passes, including all new client-isolation tests.
- [ ] `mvn verify` JaCoCo coverage maintained on any production code touched incidentally (expected none/minimal).
- [ ] SonarQube quality gate passes on the Azure Pipelines PR build.
- [ ] Harness metrics comparison (AC-047) recorded as evidence.

### Notes / open questions
- OQ-3 is resolved by this story's approach (DD-13) — confirm it satisfies the eval team's expectations before closing OQ-3.
- If AC-047 shows any metrics regression, investigate whether it's the filter clause narrowing recall versus an unrelated cause — don't silently absorb a coverage drop.

---

## MTDI-09: [OPS — NOT A CODE STORY] Per-environment cut-over runbook execution

### User story
As a **release/platform operator**,
I want **to execute the documented cut-over runbook (quiesce → drain queues → migrate index+tables → repoint alias/tables → flip `CLIENT_FILTERING_ENABLED` → resume) once per environment**,
so that **each environment's existing corpus is safely stamped with the incumbent `clientId` and enforcement is switched on with zero data loss and a clean rollback path**.

### Background
FR-16, D3, DD-11, design §F runbook and Diagram 3. **Explicitly not a development story** — operational execution of tooling and config delivered by MTDI-06/07/08, run per environment (lower environments first) by the release operator with the dev team on standby.

### Acceptance criteria
- [ ] AC-053 (delivers AC-16): Given write traffic is quiesced and queues (ingestion, answer-generation, scoring) are drained, when the operator checks queue depth and in-flight idempotency leases, then zero in-flight messages and zero live leases are confirmed before migration starts.
- [ ] AC-054: Given the operator runs the index-copy tool with `clientIdOverride=<incumbent clientId>` (OQ-4 resolved), when the run completes, then the `clientId eq null` count in the target index reaches 0.
- [ ] AC-055: Given the operator runs the table-copy tool for both tables with `partitionKeyOverride=<incumbent clientId>`, when the run completes and a final idempotent re-copy is executed immediately before the flag flip, then no rows are missed from writes that landed between the initial copy and the flip.
- [ ] AC-056: Given the index alias and table env vars are repointed and `CLIENT_FILTERING_ENABLED=true`, when traffic resumes, then a smoke test against each of the five endpoints (using the MTDI-08 suite or a subset) confirms enforcement is active (401 without header, 404 cross-client, correct filtering).
- [ ] AC-057: Given a rollback is triggered, when the operator flips the flag off and repoints table env vars to the old (untouched) tables, then the service returns to pre-cutover behaviour with **no data rewrite** (the alias may safely remain on v2, per DD-11).

### NFR links
- NFR-2 (Reversibility): AC-057 is the direct operational proof.
- NFR-1 (No new infra): confirm no new Azure resources beyond the new tables covered by the "single logical set post-migration" interpretation.
- NFR-6 (Idempotency): AC-053/AC-055 confirm no idempotency regression during the migration window.

### Out of scope for this story
- Any code change — a bug found during the runbook is a new, separate bug-fix story.
- APIM policy deployment (`cpp-azure-api-management`) — coordination dependency; this story assumes the header-injection policy is live in the target environment before enforcement flips.
- Physical retirement of old tables/index or the dual-path blob resolver.

### Definition of done
- [ ] Runbook executed and checked off step-by-step against design §F / Diagram 3, with each step's evidence (queue depths, `clientId eq null` count, row counts pre/post copy) attached to the environment's change record.
- [ ] Post-cutover smoke test (AC-056) passed and evidenced.
- [ ] Rollback procedure (AC-057) dry-run-verified in at least one lower environment before production execution.
- [ ] OQ-1 (clientId format) and OQ-4 (incumbent clientId value) confirmed and recorded before this story starts in any environment.
- [ ] Sign-off recorded from platform/release operator and tech lead per environment.

### Notes / open questions
- Run once per environment, lower environments first. Do not run in production until at least one lower environment has completed the full cycle including a rollback dry-run.
- Design Risk #3: confirm per-environment whether `AZURE_SEARCH_SERVICE_INDEX_NAME` already resolves to a v2 alias; if so, use the in-place-field-add fallback (design §B option iii) — changes the runbook's index step only.
