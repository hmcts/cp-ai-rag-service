# ADR 0002: Model access via the GA OpenAI Java SDK on the Azure `/openai/v1` passthrough surface, behind per-capability provider toggles

| | |
|---|---|
| **Status** | Accepted (2026-09-15, Mahesh Subramanian) |
| **Date** | 2026-09-15 |
| **Jira** | DD-43417 (sub-tasks DD-43420 … DD-43424) |
| **Artefacts** | `docs/pipeline/DD-43417-openai-sdk-migration/` (00-input-brief, 01-requirements, 02-design, 03-stories) |
| **Supersedes / superseded by** | — |

## Context

All model I/O — chat completion and embeddings — reaches the service's Azure-hosted OpenAI deployments through `com.azure:azure-ai-openai` **1.0.0-beta.16**, a perpetually-beta SDK. The official `com.openai:openai-java` **4.41.0** SDK is GA, already a declared dependency, and supports Azure-hosted deployments through the Azure OpenAI **v1 passthrough surface** (`{endpoint}/openai/v1`) with managed-identity bearer-token auth. Chat is already provider-switchable (`LLM_CHAT_SERVICE_PROVIDER`; the OpenAI leg is built, unit-tested and harness-evaluated at 80/80 parity); embeddings are Azure-SDK-only, and the OpenAI client is built with no retry/timeout configuration.

Constraints shaping the decision:

- **Models stay on Azure OpenAI and auth stays `DefaultAzureCredential`** — only the client SDK changes. No HTTP-contract, trigger, queue, table or blob change.
- **Reversibility until proven** — the embeddings leg has no production exercise; rollback must be a configuration change, not a redeploy, until the OpenAI path is confirmed in production (D4).
- **Azure-only diagnostics exist today** — `AzureChatService` logs content-filter categories/severities and branches on `CONTENT_FILTERED`/`TOKEN_LIMIT_REACHED`; the Responses API path does not expose the same detail (D3).
- **Six non-OpenAI Azure clients** (AI Search, Blob ×2, Table, Document Intelligence, migration-tool index admin) share `ClientConfiguration` and `azure-identity`, which must remain untouched (D5).
- **Positional vector↔chunk association** in `ChunkEmbeddingService.enrichChunksWithEmbeddings` means a silently reordered embeddings response would corrupt the AI Search index without an error.

## Decision

Complete the migration onto **openai-java 4.41.0 against the Azure `/openai/v1` surface**, staged behind **per-capability provider toggles**, with removal of the Azure SDK **deferred** behind a production-exercise gate:

1. **One toggle per capability** (DD-1): the existing `LLM_CHAT_SERVICE_PROVIDER` plus a new **`EMBEDDING_SERVICE_PROVIDER`** (`azure` | `openai`), so each leg cuts over — and rolls back — independently. `EmbeddingServiceFactory` is a line-for-line analogue of `ChatServiceFactory` (DD-2); `EmbeddingService` becomes the interface name in place, implementations renamed (`AzureEmbeddingService`, new `OpenAiEmbeddingService`), so no consumer signature, import or mock changes (DD-3).
2. **Client hardening reuses the existing env vars** (DD-4): `AZURE_CLIENT_MAX_RETRIES` → `maxRetries` (default kept at 3, DD-5) and `HTTP_CLIENT_{RESPONSE,CONNECT,WRITE}_TIMEOUT_IN_SECONDS` → `com.openai.core.Timeout` (DD-6: the response value feeds both the `request` and `read` phases, because OkHttp's read timeout bounds the first-byte wait — the model's processing time on non-streaming calls; `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` is a documented no-op on this client), applied in `OpenAiClientFactory` via a new `OpenAiClientConfiguration`. An optional `OPENAI_CLIENT_*` rename with legacy fallback is deferred to Stage 4.
3. **Explicit `Embedding::index` sort** in `OpenAiEmbeddingService` (DD-7) — the ordering guarantee is enforced and tested, never assumed, because positional mis-association is the one silent-corruption failure mode of this migration.
4. **Harness transition** (DD-8/DD-9): `PinnedApiVersionEmbeddingService` (the preview-api-version 401 workaround) transitionally extends `AzureEmbeddingService` at Stage 2 and is deleted at cut-over — the v1 surface has no preview data-plane api-version. The Azure-coupled `EmbeddingServiceTest` is renamed, not rewritten; it dies with its subject at Stage 4.
5. **Content-filter parity is a gate, not an assumption** (DD-10): a spike (DD-43422) records what a filter trip looks like through openai-java on the v1 surface; equivalent logging is added to `OpenAiChatService` if a gap is confirmed. The recorded verdict gates the Stage 4 chat deletion.
6. **Cut-over flips defaults, per environment, embeddings first** (DD-11), with the forward leg proven in CI and the rollback leg proven locally pre-merge (DD-12); the `user` request tag is kept and verified once on the v1 surface (DD-13).
7. **Deferred removal (one-way door):** only after the OpenAI path is "fully exercised in production" (criteria to be agreed — OQ-6) and the spike verdict is closed are `AzureChatService`, `AzureEmbeddingService`, `AzureOpenAiClientFactory` and `com.azure:azure-ai-openai` deleted; the factories then fail fast on a stale `provider=azure` setting with an explicit "provider removed" error. `com.azure:azure-identity` and `ClientConfiguration` are retained for the bearer supplier and the six other Azure clients.

## Alternatives considered

- **Big-bang replacement (delete the Azure SDK in one PR):** makes rollback a redeploy rather than an app-setting flip, couples the unproven embeddings leg to the proven chat leg, and forecloses the content-filter spike gate. Rejected.
- **A single `MODEL_SDK_PROVIDER` toggle for chat + embeddings:** forces both legs to cut over together and removes per-leg rollback; the two legs have very different levels of production evidence. Rejected.
- **Hand-rolled HTTP client abstraction:** re-implements auth, retries and streaming for no benefit; trades a GA vendor SDK for bespoke code. Rejected.
- **New `OPENAI_CLIENT_*` env vars at Stage 1:** requires a coordinated app-settings change in every environment before a behaviour-neutral PR can deploy, and lets the two toggle legs drift in tuning. Deferred to Stage 4 as an optional rename with fallback.

The full decision list (DD-1 … DD-13, with per-decision alternatives) is in `02-design.md`.

## Consequences

**Positive**
- Model access moves to a GA, actively maintained SDK; the perpetual-beta dependency is retired.
- Rollback stays configuration-only (per leg) through cut-over; embedding vectors from either SDK are interchangeable (same deployment/model), so there is no data unwind.
- The harness's pinned-api-version workaround — and the misleading-401 failure class behind it — disappears with the versioned data-plane surface.
- One provider-switch idiom (factory + env toggle) now covers both model capabilities; no new pattern to learn.

**Negative / accepted risks**
- **Backoff configurability is lost**: openai-java's retry curve is fixed (0.5 s → 8 s + jitter, `Retry-After` honoured); `AZURE_CLIENT_BASE_DELAY_IN_SECONDS`/`MAX_DELAY` become documented no-ops on this client. Only max-retries carries over.
- **Content-filter diagnostics are reduced** on the Responses API path — **accepted by decision (2026-09-15)**: content filtering is deliberately disabled on all model resources (sensitive case material must not be truncated), so filter events cannot occur in normal operation and no replacement logging ships; the spike verdict + decision (`04-content-filter-parity-findings.md`) close the D3 gate on the Stage 4 deletion.
- **One-way door at Stage 4**: after deletion, reverting to the Azure SDK is a code revert and redeploy — which is exactly why the removal is deferred behind the production-exercise gate.
- Two live model-plumbing paths persist until Stage 4; the gate criteria (OQ-6) must be defined so the deferral does not drift indefinitely.
