# ADR 0001: Logical multi-client isolation via a single `clientId` namespace

| | |
|---|---|
| **Status** | Proposed (pending tech-lead sign-off) |
| **Date** | 2026-07-20 |
| **Jira** | DD-42722 (sub-tasks DD-42976 … DD-42984) |
| **Artefacts** | `docs/pipeline/DD-42722-multi-tenant-data-isolation/` (00-input-brief, 01-requirements, 02-design, 03-stories) |
| **Supersedes / superseded by** | — |

## Context

The CP AI RAG Service is single-consumer by construction: APIM validates one Entra ID application's JWT and the function backends receive no caller identity. All ingested documents, AI Search chunks, Table Storage rows and blobs share one namespace, so any caller holding a UUID (`documentReference` / `transactionId`) can read any other caller's status or answer payload. The service must onboard multiple clients (HMCTS-internal teams and external partners) with per-client data isolation.

Constraints shaping the decision:

- **No new infrastructure resources** — the single AI Search index, storage account and set of tables are retained.
- **The upstream consumer-identity mechanism (AMP / API Marketplace) is owned by another team and not yet concrete** — the backend may receive a header today, a JWT or cookie later.
- **An existing single-client production corpus** must be migrated, not orphaned, and a hand-run migration module (`ai-document-migration-tool`) already exists: an index→index copier (pending v2 schema rebuild with alias cutover) and a table→table copier with `partitionKeyOverride`.
- **Reversibility** — cut-over must be roll-back-able without a data rewrite.
- The recently-landed **idempotency guard** leases and fences on the very table rows whose keying this decision changes.

## Decision

Adopt **logical isolation** with a single `clientId` attribute (UUID, validated at the boundary) threaded through every data path, enforced behind a feature flag:

1. **Identity at the edge, abstracted once.** APIM (per AMP standards) identifies the consumer and passes it downstream; the backend reads it through one shared abstraction — `ClientIdentityResolver` / `ClientContext` in `ai-document-shared-artefacts` (header-based default, header name configurable via `CLIENT_IDENTITY_HEADER`). This is the single point of change when AMP finalises. The header is an **internal APIM↔function contract**, not part of the consumer OpenAPI spec. Missing/invalid identity → 401 when enforcement is on.
2. **`clientId` as the namespace everywhere in the data plane:**
   - AI Search: top-level filterable (non-searchable) `clientId` field, added to the pending **v2** schema rebuild; a non-optional leading `clientId eq '…'` OData clause on every query and on the ingestion supersede/delete filter.
   - Table Storage: partition key = `clientId`, row key = `documentId`/`transactionId` (new tables populated by the existing table copier; env vars repointed at cut-over). The idempotency store/guard becomes client-aware (`(clientId, key)`), preserving all lease/fencing semantics; null clientId falls back to today's PK==RK==key.
   - Blobs: new blobs prefixed `c={clientId}/…` with a dual-path resolver for legacy flat names; the Table row — not the path — is the authoritative ownership record.
   - Queues: `clientId` is a non-optional payload field (and rides the `ScoringPayload` blob for the scorer), so it survives redelivery.
   - Telemetry: a `client_id` metric dimension.
3. **Enforcement behind `CLIENT_FILTERING_ENABLED`** (default off = byte-for-byte today's behaviour). The single deliberate exception: rejection of reserved `metadataFilter` keys (`clientId`, `is_active` → 400) is **always-on**.
4. **Cross-client lookups return 404** (never 403) as a natural consequence of partition scoping — no existence leakage.
5. **`documentId` uniqueness is per client** — two clients may reuse the same id without collision (chunk ids are random UUIDs, so no index key collision).
6. **Migration** extends the existing tool: a `clientIdOverride` on the index copier (analogue of the table copier's `partitionKeyOverride`) stamps the incumbent client during the single v2 rebuild. Cut-over is per environment with **drained queues** (no dual-write): quiesce → drain → migrate → final idempotent table re-copy → repoint alias + table env vars → flip flag → resume.

## Alternatives considered

- **Index-per-client** (one AI Search index per client, dynamic index resolution): removes the filter-bypass vulnerability class entirely, but multiplies index lifecycle (schema upgrades, capacity, tuning, cost attribution) by N and runs into per-SKU index caps. Rejected as the starting point.
- **Full silo** (function app + storage + search + OpenAI per client): maximal isolation, cost and operational overhead proportional to tenant count; only justified by regulation or extreme noisy-neighbour concerns that do not apply. Rejected.
- **Reusing `customMetadata` for tenancy**: co-locates tenancy with caller-controlled data, conflating trust boundaries. Rejected in favour of a top-level field.
- **Dual-write transition mode** instead of drained-queue cut-over: more machinery for a window the queue drain eliminates. Rejected.
- **Per-function header parsing** instead of one shared resolver: no single point of change when AMP finalises. Rejected.

The full decision list (design decisions 1–13, with per-decision alternatives) is in `02-design.md`.

## Consequences

**Positive**
- Isolation with zero new infrastructure; onboarding a new client is APIM configuration only.
- `clientId`-as-namespace preserves the option to migrate an individual client to its own index or full silo later via routing/config only.
- Flag + table-env-var rollback requires no data rewrite; old tables and v1 index stay untouched through the reversibility window.
- Every merge before cut-over is behaviour-neutral (flag off), so delivery is incremental and low-risk.

**Negative / accepted risks**
- **One-way doors:** (a) the partition-key choice — once clients depend on `(clientId, key)` keying, un-isolating requires another migration; (b) the index alias repoint — harmless to leave on v2 during rollback, but the v1 index becomes stale the moment post-cutover ingestion begins.
- Isolation correctness depends on the leading OData clause and partition scoping being applied on **every** path — a broad mechanical fan-out (idempotency store, both table services, both workers) that must be regression-tested exhaustively (the highest-risk story, MTDI03/DD-42978).
- A compromised or misconfigured APIM layer defeats edge identity; mitigated by function-key + network restriction and the always-on reserved-key rejection, but the backend ultimately trusts the injected identity.
- Per-client metric cardinality grows linearly with onboarded clients (bounded, acceptable).

**Follow-ups**
- Coordination dependency: `cpp-azure-api-management` must inject the header matching `CLIENT_IDENTITY_HEADER` before production enforcement.
- Open before cut-over: final `clientId` format confirmation (UUID assumed) and the incumbent client's concrete value (migration-tool argument).
- Deferred: general `metadataFilter` allow-list; legacy blob retirement; per-client rate limits/quotas (AMP).
