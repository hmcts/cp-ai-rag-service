# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build all modules
mvn clean compile

# Run all unit tests (all modules)
mvn test

# Run a single test class
mvn test -pl ai-document-ingestion-function -Dtest=DocumentIngestionFunctionTest

# Run a single test method
mvn test -pl ai-document-ingestion-function -Dtest=DocumentIngestionFunctionTest#someMethodName

# Run tests with coverage report
mvn verify

# Run integration tests (skipped by default; activate the profile in ai-service-orchestration-test)
mvn verify -P ai-rag-integration-test

# Build and package the Azure Functions
mvn clean package -DskipTests

# Run a specific Azure Function locally (from the function's directory)
cd ai-document-ingestion-function && mvn azure-functions:run
```

## Local Development Setup

Each function module has an `Azure/local.settings.sample.json`. Copy it to `Azure/local.settings.json` (git-ignored) and populate the real endpoints/account names before running locally. Storage-account access is **managed identity only** (`DefaultAzureCredential`) — there are no storage connection strings or account keys.

Key environment variables required across functions:
- `AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT` / `AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT` — storage endpoints the SDK client factories authenticate against via managed identity
- `AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING` — **not** a connection string: it is the name of the identity-based binding used by the Functions storage triggers/outputs (the host resolves the matching `..._CONNECTION_STRING__accountName` app setting and authenticates via managed identity)
- `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION` — queue name for ingestion messages
- `AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT` — for document content extraction
- `AZURE_SEARCH_SERVICE_ENDPOINT` + `AZURE_SEARCH_SERVICE_INDEX_NAME` — AI Search
- Retrieval tuning (answer-retrieval function): `SEARCH_NEAREST_NEIGHBOURS_COUNT` / `SEARCH_TOP_RESULTS_COUNT` size the over-fetched candidate pool. The post-retrieval pipeline (`AzureAISearchService.search`) runs containment dedup → semantic dedup → MMR:
  - `SEARCH_RESULTS_ENABLE_CONTAINMENT_DEDUP` toggles information-safe containment dedup (`ContentContainmentService`) — drops a chunk only when its content is already covered by a higher-ranked chunk, so cross-file duplicate copies collapse while a "copy + extra crucial info" superset is preserved. `SEARCH_CONTAINMENT_SHINGLE_SIZE` (word n-gram size, default 3) and `SEARCH_CONTAINMENT_THRESHOLD` (coverage fraction to drop, default 0.95) tune it.
  - `SEARCH_RESULTS_ENABLE_MMR` toggles MMR diversification (`DiversificationService`); `SEARCH_MMR_LAMBDA` (0..1, lower = more diverse) and `SEARCH_MMR_FINAL_COUNT` (chunks sent to the LLM — must be < the candidate pool) tune it.
  - `SEARCH_RESULTS_ENABLE_DEDUPLICATION` / `SEARCH_RESULTS_SEMANTIC_DEDUPLICATION_THRESHOLD` drive the older cosine semantic-dedup path, off by default (not information-safe — can drop a near-duplicate that carries unique content).
- `AZURE_EMBEDDING_SERVICE_ENDPOINT` + `AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME`
- `LLM_MODEL_RESPONSE_MAX_TOKENS`, HTTP/retry config (`AZURE_CLIENT_MAX_RETRIES`, etc.)

## Module Structure

Multi-module Maven project with five Azure Functions, one shared library, and one integration-test module:

| Module | Purpose |
|--------|---------|
| `ai-document-shared-artefacts` | Shared models (OpenAPI-generated), entities, utility services used by all functions |
| `ai-document-metadata-check-function` | HTTP `POST /document-upload` issues a SAS URL for the file upload; blob triggers then validate metadata against Table Storage and enqueue the ingestion message |
| `ai-document-ingestion-function` | Queue-triggered; orchestrates Document Intelligence → chunking → embedding → AI Search indexing |
| `ai-document-answer-retrieval-function` | Queue-triggered; embeds query, retrieves chunks via AI Search, generates LLM answer summary |
| `ai-document-answer-scoring-function` | Evaluates response groundedness, publishes scores to Azure Monitor |
| `ai-document-status-check-function` | HTTP-triggered; exposes GET endpoints to retrieve document ingestion status from Table Storage |
| `ai-service-orchestration-test` | Integration tests (REST Assured + Testcontainers + Awaitility) — test-only module |

## API Contract

The HTTP API is **contract-first**. The OpenAPI 3.0.0 spec lives in a separate,
spec-only repo — **[`hmcts/api-cp-ai-rag`](https://github.com/hmcts/api-cp-ai-rag/)** — at
[`src/main/resources/openapi/ai-rag-service.openapi.yml`](https://github.com/hmcts/api-cp-ai-rag/blob/main/src/main/resources/openapi/ai-rag-service.openapi.yml). That spec is the source
of truth for request/response shapes; the `uk.gov.hmcts.cp.openapi` models in
`ai-document-shared-artefacts` are generated against it. Do **not** hand-edit the
generated models or change an HTTP function's request/response contract without
first updating the spec in `api-cp-ai-rag` (it is Gradle-built, Spectral-linted
via `.spectral.yml`, and its docs are published by GitHub Actions). When changing
an endpoint here, treat it as: update the spec repo → regenerate/realign models →
implement.

### Endpoints (per the contract)

| Method & path (contract) | operationId | Request | Success | Errors | Implemented by (`@FunctionName`) |
|---|---|---|---|---|---|
| `POST /document-upload` | `initiate-document-upload` | `documentUploadRequest` | `200` `fileStorageLocationReturnedSuccessfully` | `400`/`500` `requestErrored` | `InitiateDocumentUpload` |
| `GET /document-upload/{documentReference}` | `document-status-by-reference` | path `documentReference` (uuid) | `200` `documentIngestionStatusReturnedSuccessfully` | `404` `documentStatusNotAvailable` | `DocumentStatusByReference` |
| `GET /document-status` | `document-status` | query `document-name` | `200` `documentIngestionStatusReturnedSuccessfully` | `404` `documentStatusNotAvailable` | `DocumentStatusCheck` |
| `POST /answer-user-query` | `answer-user-query` | `answerUserQueryRequest` | `200` `userQueryAnswerReturnedSuccessfullySynchronously` | `400`/`500` `requestErrored` | `AnswerRetrieval` |
| `POST /answer-user-query-async` | `answer-user-query-async` | `answerUserQueryRequest` | `202` `userQueryAnswerRequestAccepted` | `400`/`500` `requestErrored` | `InitiateAnswerGeneration` |
| `GET /answer-user-query-async-status/{transactionId}` | `answer-user-query-status` | path `transactionId` (uuid), query `withChunkedEntries` (bool) | `200` `userQueryAnswerReturnedSuccessfullyAsynchronously` | `400` `requestErrored` | `GetAnswerGeneration` |

### Key schemas
- **Requests:** `documentUploadRequest` (documentId, documentName, metadataFilter[], optional overwrites[]), `answerUserQueryRequest` (userQuery, queryPrompt, metadataFilter[]).
- **Responses:** `fileStorageLocationReturnedSuccessfully` (storageUrl + documentReference), `documentIngestionStatusReturnedSuccessfully`, `userQueryAnswerReturnedSuccessfully{Synchronously,Asynchronously}`, `userQueryAnswerRequestAccepted` (transactionId), `documentStatusNotAvailable`, `requestErrored`.
- **Building blocks:** `uuid` (regex-constrained), `metadataFilter` (key/value, each ≤40 chars), `documentChunk` (documentId, documentName, pageNumber, chunkContent, customMetadata[]).
- **Enums:** `documentIngestionStatus` = `INGESTION_SUCCESS` | `INGESTION_FAILED` | `METADATA_VALIDATED` | `INVALID_METADATA` | `AWAITING_UPLOAD` | `AWAITING_INGESTION` | `FILE_SIZE_OVER_LIMIT`; `answerGenerationStatus` = `ANSWER_GENERATED` | `ANSWER_GENERATION_FAILED` | `ANSWER_GENERATION_PENDING`.

### Known contract ↔ implementation drift
Two HTTP functions declare no explicit `route`, so the Functions host derives the
route from the `@FunctionName` value rather than the contract path:
- `AnswerRetrieval` — contract path is `POST /answer-user-query`.
- `DocumentStatusCheck` — contract path is `GET /document-status` (query `document-name`).

Add explicit `route = "..."` attributes to align these with the spec. Run the
`api-contract-check` skill to re-verify after changes.

## Architecture & Data Flow

### Document Ingestion Pipeline

The metadata-check module exposes two upload entry flows; both converge on `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION` and the same downstream worker.

**Flow A — HTTP-initiated SAS upload** (preferred, two-step):
1. Caller calls `DocumentUploadFunction` (`POST /document-upload`, `@FunctionName("InitiateDocumentUpload")`) with a `DocumentUploadRequest` (documentId, documentName, metadata, overwrites). The function validates the request, rejects duplicates, records an "awaiting upload" row in Table Storage, and returns a `FileStorageLocationReturnedSuccessfully` payload containing a write-only SAS URL (generated by `BlobClientService.getSasUrl`, expiry controlled by `SAS_STORAGE_URL_EXPIRY_MINUTES`) for the `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD` container, plus the documentId.
2. Caller PUTs the file bytes directly to the SAS URL.
3. The upload triggers `DocumentBlobTriggerFunction` (`@FunctionName("DocumentUploadCheck")`), which checks blob size against `MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB`, updates the Table Storage row's status, and enqueues a `QueueIngestionMetadata` message to `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION`.

**Flow B — direct blob drop** (file lands in `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME` via an out-of-band mechanism):
1. `BlobTriggerFunction` (`@FunctionName("DocumentMetadataCheck")`) fires; `IngestionOrchestratorService` validates metadata against Table Storage and enqueues an ingestion message on success.

**Downstream (shared by both flows):**
- `DocumentIngestionFunction` (ingestion-function, queue-triggered) consumes the queue and runs `DocumentIngestionOrchestrator`:
  - Azure Document Intelligence extracts text content
  - Content is chunked by `DocumentChunkingService` (uses LangChain4J's recursive `DocumentSplitter`)
  - Embeddings generated via Azure OpenAI
  - Chunks + embeddings stored in Azure AI Search index

### Query & Answer Generation Pipeline

The answer-retrieval module exposes two HTTP invocation modes plus the queue-triggered async worker.

**Synchronous** — single round-trip:
- `SyncAnswerGenerationFunction` (`POST` `AnswerRetrieval`) — embeds the query (`EmbedDataService`), retrieves chunks (`AzureAISearchService`), calls Azure OpenAI via `ResponseGenerationService`/`ChatService`, and returns the answer in the HTTP response. Also enqueues a scoring message to `STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING`.

**Asynchronous** — request/poll across three functions:
1. `InitiateAnswerGenerationFunction` (`POST /answer-user-query-async`) validates the request, writes a pending row to `STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION`, enqueues a payload to `STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION`, and returns a `transactionId`.
2. `AnswerGenerationFunction` (queue-triggered on `STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION`) runs the same embed → search → LLM flow, persists the result payload to Blob Storage, updates Table Storage status, and enqueues a scoring message.
3. `GetAnswerGenerationResultFunction` (`GET /answer-user-query-async-status/{transactionId}`) is the polling endpoint that returns the generated answer once ready.

### Retrieval Refinement Pipeline (post-retrieval, in `AzureAISearchService.search`)

Both invocation modes share the same retrieval path. `AzureAISearchService` over-fetches a candidate pool (vector + keyword), then runs three **independently toggled** stages, in order, before chunks reach the LLM. Azure AI Search has no server-side dedup/diversity operator, so this is done client-side. The search service is deliberately **agnostic of the toggles** — it always selects the `chunkVector` column and each stage owns its own enable flag and config:

1. `ContentContainmentService` — **information-safe** dedup. Drops a chunk only when (nearly) all of its content already appears in a higher-ranked retained chunk, via asymmetric word n-gram *containment*. Collapses duplicate passages that legitimately live in different files (so they cannot be deduplicated at ingestion, where per-file provenance/filtering must be preserved) while **never discarding a chunk that carries unique information** (a "copy + extra crucial sentence" superset survives).
2. `DeduplicationService` — coarser, symmetric cosine-similarity dedup. **Off by default**: it can drop a near-duplicate that actually carries unique content, so it is superseded by containment dedup. See its class Javadoc.
3. `DiversificationService` — MMR (Maximal Marginal Relevance). Selects a relevance-vs-diversity balanced subset and truncates to the final chunk count sent to the LLM, cutting token usage.

Cross-file duplication only surfaces when a query's metadata filter spans multiple files; with a single-file filter there is little for these stages to collapse. The env vars that tune this pipeline, and how they interact, are documented in the "Key environment variables" section above and in `ai-document-answer-retrieval-function/Azure/local.settings.sample.json`.

#### Sizing the count variables (must hold)

The three count variables form a chain and must satisfy:

```
SEARCH_NEAREST_NEIGHBOURS_COUNT  ≥  SEARCH_TOP_RESULTS_COUNT  >  SEARCH_MMR_FINAL_COUNT
        (vector recall)                 (candidate pool)            (chunks to the LLM)
```

- **kNN ≥ pool:** `SEARCH_NEAREST_NEIGHBOURS_COUNT` is how many candidates the vector subquery returns. If the pool is larger than kNN, the vector side cannot fill it and the surplus falls back to keyword matches — so keep kNN at least the pool size.
- **pool > final (with headroom):** `SEARCH_TOP_RESULTS_COUNT` is the candidate pool the refinement stages shrink; MMR truncates it to `SEARCH_MMR_FINAL_COUNT`. If the pool is not larger than the final count, MMR has nothing to diversify over and below-cut unique chunks never enter. Because containment/semantic dedup may remove chunks before MMR, leave real headroom (not `+1`).

Default sizing — kNN `50` ≥ pool `50` > final `15` — satisfies this. If you raise the pool, raise kNN with it; if you raise the final count, raise the pool above it.

### Scoring
- `AnswerScoringFunction` evaluates answer groundedness via `ScoringService`
- `PublishScoreService` records metrics to Azure Monitor

### Key Shared Components (ai-document-shared-artefacts)
- OpenAPI-generated models under `uk.gov.hmcts.cp.openapi`
- Azure service clients (Search, Table, Blob, Document Intelligence, OpenAI)
- HTTP client utilities with configurable retry logic

## Branch & Release Strategy

Uses JGitFlow Maven Plugin:
- `main` = develop branch (current working branch)
- `dev/release` = release/master branch
- Feature branches: `dev/feature-*`
- Release branches: `dev/release-*`
- Hotfix branches: `dev/hotfix-*`

## CI

CI runs on Azure Pipelines (`azure-pipelines.yaml`), triggered automatically on PR. SonarQube project key: `uk.gov.moj.cp.azure.ragservice:cp-ai-rag-service`.

## SDLC Orchestrator (hmcts-sdlc-orchestrator plugin) — Azure Functions adaptation

The `hmcts-sdlc-orchestrator` plugin ships an 8-stage SDLC pipeline built for
Spring Boot services on AKS. This repo is **multi-module Maven Azure Functions**,
so the pipeline *shape* is reused but the build and runtime stages are
overridden locally. Precedence: project `.claude/` files override same-named
plugin files.

- **Read first:** `.claude/context/azure-functions.md` — the authoritative deltas
  (Maven not Gradle, `@FunctionName` not controllers, no actuator probes,
  `context.getLogger()` not logback, Azure DevOps not GitHub Actions,
  connection-strings as a tracked deviation). It supersedes the plugin's
  `tech-stack.md`, `azure-cloud-native.md`, and `logging-standards.md`.
- **Overridden agents** (`.claude/agents/`): `implementation`, `doc-generator`,
  and `ci-orchestrator` — rewritten for Functions. Note `ci-orchestrator` is
  **monitor + triage only** (read-only): CI auto-triggers on PR, so it observes
  and triages the existing run, it never triggers a build.
- **Out of scope locally:** CI is **never triggered from a local machine** — it runs
  automatically on PR. The local agents therefore cover implementation, doc
  generation, and CI triage only.
- **Reuse from the plugin as-is:** `requirements-analyst`, `architecture-designer`,
  `story-writer`, `test-engineer`, `research`, `test-analyzer`, `code-reviewer`
  (skip its Spring Boot template-alignment / actuator checks), `api-contract-check`,
  the security hooks (`block-secrets`, `block-pii`, `guard-bash`, `guard-paths`).
- **Do NOT use:** `springboot-service-from-template`, `springboot-api-from-template`,
  `context-scaffold`, `context-service-guide`, `helm-config-validator`,
  `terraform-validate` — no equivalent here.
- Pipeline artefacts still go to `docs/pipeline/` per the plugin convention.
