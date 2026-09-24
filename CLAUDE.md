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

# Run integration tests (skipped by default; runs against real Azure via locally started
# function hosts). Config comes from ai-service-orchestration-test/.env — copy .env.sample,
# populate it, `az login`, then:
./ai-service-orchestration-test/run-integration-test.sh
# (equivalent to: mvn verify -P ai-rag-integration-test — with the .env exported first)

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
- Model SDK provider toggles (DD-43417 migration): `LLM_CHAT_SERVICE_PROVIDER` and `EMBEDDING_SERVICE_PROVIDER` each accept `azure` | `openai` and **both default to `openai`** (flipped at the Stage 3 cut-over, DD-43423), selecting the implementation `ChatServiceFactory` / `EmbeddingServiceFactory` construct. `openai` uses the GA `openai-java` SDK against the Azure `/openai/v1` passthrough (chat via the Responses API); `azure` keeps the `azure-ai-openai` SDK and remains the explicit, config-only rollback value (no redeploy, no data migration) until the Stage 4 removal (DD-43424). An unrecognised value fails fast at construction. Retry/timeout config on the `openai` leg comes from the same `AZURE_CLIENT_MAX_RETRIES`/`HTTP_CLIENT_*` vars via `OpenAiClientConfiguration` (DD-43420) — see the HTTP/retry entry below for the mapping and the vars that stay Azure-SDK-only.
- `LLM_MODEL_RESPONSE_MAX_TOKENS`, HTTP/retry config (`AZURE_CLIENT_MAX_RETRIES`, `HTTP_CLIENT_{RESPONSE,CONNECT,READ,WRITE}_TIMEOUT_IN_SECONDS`, etc.). These same vars also configure the openai-java client via `OpenAiClientConfiguration` — `AZURE_CLIENT_MAX_RETRIES` → `maxRetries`, and `HTTP_CLIENT_{RESPONSE,CONNECT,WRITE}_TIMEOUT_IN_SECONDS` → the request/connect/write phases of `com.openai.core.Timeout`. The `read` phase also takes the **response**-timeout value: it maps onto OkHttp's read timeout, which bounds the wait for the first response byte (i.e. all of the model's processing time on these non-streaming calls), so binding it to the read var would cap model latency far below the Azure/Netty leg. `AZURE_CLIENT_BASE_DELAY_IN_SECONDS`/`AZURE_CLIENT_MAX_DELAY_IN_SECONDS` and `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` apply **only** to the Azure SDK clients (`ClientConfiguration`) and are never read on the OpenAI path — the OpenAI SDK's backoff curve is also fixed (~0.5 s → 8 s with jitter; `X-Should-Retry` decides whether to retry, `Retry-After`/`Retry-After-Ms` shape the delay) and is not configurable.
- Citation guard (answer-retrieval): `CITATION_GUARD_MODE` = `deliver` (default) | `reject` | `off`. When an answer comes back citation-degraded (no `<FACT_MAP_JSON>` block, unparseable JSON, or every inline marker stripped), `ResponseGenerationService` throws `CitationDegradedException` (single LLM attempt — no in-process retry loop). **Async path**: the exception rides queue redelivery — each retry is a fresh, short invocation (re-embed → re-search → one LLM call) up to `maxDequeueCount` (host.json, 3), with the idempotency lease released before each rethrow so the retry can re-claim immediately; at exhaustion `deliver` persists+scores the degraded answer with the guard reason in the table's reason column, `reject` records a FAILED row (no blob/scoring). **Sync path**: no retries — the policy applies immediately (deliver → 200 with the degraded answer; reject → 200 with the failure sentinel, scoring skipped). `off` disables evaluation (pre-guard behaviour). Deliberate no-evidence refusals (empty `FACT_MAP_JSON[]`, no markers) always pass.
- Idempotency guard (both queue workers): `IDEMPOTENCY_LEASE_TTL_SECONDS` (default 300) sizes the in-progress lease — it must exceed a worst-case single attempt but stay **below** `visibilityTimeout × (maxDequeueCount − 1)`, or a crashed leaseholder's lease outlives the retry budget. `IdempotencyGuard.runOnce(key, work)` (shared artefacts, `uk.gov.moj.cp.ai.idempotency`) wraps the expensive pipeline keyed on `transactionId`/`documentId`: a duplicate delivery for an already-terminal row (`ANSWER_GENERATED`/`*_FAILED`, `INGESTION_SUCCESS`/`INGESTION_FAILED`/`FILE_SIZE_OVER_LIMIT`) is skipped without LLM/embedding calls or scoring re-enqueue; otherwise the worker claims a lease on the status row via ETag/If-Match conditional MERGE (columns `LeaseOwner`/`LeaseExpiresAt`, internal — no API change). The claim-time ETag fences the terminal write: a worker whose expired lease was reclaimed gets a 412 (`EtagMismatchException`), discards its result, and never enqueues scoring. A duplicate that finds a live lease rethrows to redeliver and re-check; if delivery attempts exhaust against a live lease the worker logs a WARN and does **not** write FAILED over the possibly-completing leaseholder (crashed-leaseholder rows stay in their non-terminal status — `ANSWER_GENERATION_PENDING` / `AWAITING_INGESTION` — alert on that WARN). Design: `docs/idempotency-rag-service.md` / Confluence page 1990369819.
- Client identity (multi-client isolation, DD-42722 — functions/workers now wired behind the flag; enforcement activates only at cut-over): `CLIENT_FILTERING_ENABLED` (default `false`) gates client-identity enforcement. Off preserves pre-multi-client behaviour exactly — the resolver returns an unenforced context, so the `clientId` stays null everywhere (no filter clause, legacy table/blob keying, single-dimension telemetry). On makes the APIM-injected identity header mandatory: the five HTTP functions reject a missing/invalid header with **401** (shared `HttpResponses.unauthorized`) and thread the resolved `clientId` into their search / dedup / table / queue-payload / blob-name calls; the two queue workers and the scorer recover it from the payload (re-validated via `ClientId.requireValid` when present) and scope their fenced writes, search, prefixed blobs and the `client_id` telemetry dimension to it; a cross-client lookup naturally resolves to **404**. `CLIENT_IDENTITY_HEADER` (default `X-Client-Id`) names the internal header the shared `HeaderClientIdentityResolver` (`uk.gov.moj.cp.ai.client.identity`, shared artefacts) reads — the single point of change when the AMP consumer-identity mechanism is finalised. Design: `docs/pipeline/DD-42722-multi-tenant-data-isolation/02-design.md`.
- `LLM_REASONING_EFFORT` — optional; applied by `AzureChatService` **only to reasoning models** (gpt-5/o-series), ignored for gpt-4o. On a reasoning deployment this shares the `max_completion_tokens` budget with the answer, so higher effort can exhaust it and return an empty `finish_reason=length` response. The evaluation (`ai-document-system-prompt-harness-eval/src/main/resources/system-prompt-evaluation-cross-model.md`) found `none` eliminates those truncations on gpt-5.1 with no measured citation-coverage loss; `AzureChatService` therefore **defaults reasoning models to `none`** when the var is unset (override with `minimal|low|medium|high`).

## Module Structure

Multi-module Maven project with five Azure Functions, one shared library, and one integration-test module:

| Module | Purpose |
|--------|---------|
| `ai-document-shared-artefacts` | Shared models (OpenAPI-generated), entities, utility services used by all functions |
| `ai-document-metadata-check-function` | HTTP `POST /document-upload` issues a SAS URL for the file upload; a blob trigger then validates the uploaded file, records status in Table Storage, and enqueues the ingestion message |
| `ai-document-ingestion-function` | Queue-triggered; orchestrates Document Intelligence → chunking → embedding → AI Search indexing |
| `ai-document-answer-retrieval-function` | Queue-triggered; embeds query, retrieves chunks via AI Search, generates LLM answer summary |
| `ai-document-answer-scoring-function` | Evaluates response groundedness, publishes scores to Azure Monitor |
| `ai-document-status-check-function` | HTTP-triggered; exposes GET endpoints to retrieve document ingestion status from Table Storage |
| `ai-service-orchestration-test` | Integration tests (REST Assured + Testcontainers + Awaitility) — test-only module |
| `ai-document-system-prompt-harness-eval` | Offline, on-demand evaluation harness for the answer-retrieval system prompt (runs the embed→search→generate→cite pipeline across prompts × LLMs × queries, reports citation/verbosity/coverage metrics). Not deployed; config from a local `.env` via `run-harness.sh`. |

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
| `POST /document-upload` | `initiate-document-upload` | `documentUploadRequest` | `200` `fileStorageLocationReturnedSuccessfully` | `400`/`401`/`500` `requestErrored` | `InitiateDocumentUpload` |
| `GET /document-upload/{documentReference}` | `document-status-by-reference` | path `documentReference` (uuid) | `200` `documentIngestionStatusReturnedSuccessfully` | `400`/`401`/`404`/`500` `requestErrored` | `DocumentStatusByReference` |
| `POST /answer-user-query` | `answer-user-query` | `answerUserQueryRequest` | `200` `userQueryAnswerReturnedSuccessfullySynchronously` | `400`/`401`/`500` `requestErrored` | `AnswerRetrieval` |
| `POST /answer-user-query-async` | `answer-user-query-async` | `answerUserQueryRequest` | `202` `userQueryAnswerRequestAccepted` | `400`/`401`/`500` `requestErrored` | `InitiateAnswerGeneration` |
| `GET /answer-user-query-async-status/{transactionId}` | `answer-user-query-status` | path `transactionId` (uuid), query `withChunkedEntries` (bool) | `200` `userQueryAnswerReturnedSuccessfullyAsynchronously` | `400`/`401`/`404`/`500` `requestErrored` | `GetAnswerGeneration` |

### Key schemas
- **Requests:** `documentUploadRequest` (documentId, documentName, metadataFilter[], optional overwrites[]), `answerUserQueryRequest` (userQuery, queryPrompt, metadataFilter[]).
- **Responses:** `fileStorageLocationReturnedSuccessfully` (storageUrl + documentReference), `documentIngestionStatusReturnedSuccessfully`, `userQueryAnswerReturnedSuccessfully{Synchronously,Asynchronously}`, `userQueryAnswerRequestAccepted` (transactionId), `documentStatusNotAvailable`, `requestErrored`.
- **Building blocks:** `uuid` (regex-constrained), `metadataFilter` (key/value, each ≤40 chars), `documentChunk` (documentId, documentName, pageNumber, chunkContent, customMetadata[]).
- **Enums:** `documentIngestionStatus` = `INGESTION_SUCCESS` | `INGESTION_FAILED` | `METADATA_VALIDATED`¹ | `INVALID_METADATA`¹ | `AWAITING_UPLOAD` | `AWAITING_INGESTION` | `FILE_SIZE_OVER_LIMIT`; `answerGenerationStatus` = `ANSWER_GENERATED` | `ANSWER_GENERATION_FAILED` | `ANSWER_GENERATION_PENDING`. (¹ deprecated — only ever produced by the decommissioned direct-blob-drop flow; retained for backward compatibility with historical records.)

### Contract alignment notes
All HTTP functions declare explicit `route` attributes matching the contract paths
(`AnswerRetrieval` serves `route = "answer-user-query"`). Error responses (400/404/500,
all `requestErrored`) are documented in the spec as of the `dev/align-error-responses`
spec change; `401` (missing/invalid client identity, enforcement-gated) is documented on
all five operations as of spec release 0.0.15 — the identity header itself is deliberately
NOT a documented request parameter (internal APIM→functions contract). Run the
`api-contract-check` skill to re-verify after endpoint changes.

## Architecture & Data Flow

### Document Ingestion Pipeline

The metadata-check module exposes an HTTP-initiated SAS upload flow that feeds `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION` and the downstream worker.

**HTTP-initiated SAS upload** (two-step):
1. Caller calls `DocumentUploadFunction` (`POST /document-upload`, `@FunctionName("InitiateDocumentUpload")`) with a `DocumentUploadRequest` (documentId, documentName, metadata, overwrites). The function validates the request, rejects duplicates, records an "awaiting upload" row in Table Storage, and returns a `FileStorageLocationReturnedSuccessfully` payload containing a single-blob upload SAS URL (create/write/read, no list/delete; generated by `BlobClientService.getSasUrl`, expiry controlled by `SAS_STORAGE_URL_EXPIRY_MINUTES`, default 120 minutes) for the `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD` container, plus the documentId.
2. Caller PUTs the file bytes directly to the SAS URL.
3. The upload triggers `DocumentBlobTriggerFunction` (`@FunctionName("DocumentUploadCheck")`), which checks blob size against `MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB`, updates the Table Storage row's status, and enqueues a `QueueIngestionMetadata` message to `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION`.

**Downstream:**
- `DocumentIngestionFunction` (ingestion-function, queue-triggered) consumes the queue and runs `DocumentIngestionOrchestrator`:
  - Azure Document Intelligence extracts text content
  - Content is chunked by `DocumentChunkingService` (uses LangChain4J's recursive `DocumentSplitter`)
  - Embeddings generated via Azure OpenAI
  - Chunks + embeddings stored in Azure AI Search index

### Query & Answer Generation Pipeline

The answer-retrieval module exposes two HTTP invocation modes plus the queue-triggered async worker.

**Synchronous** — single round-trip:
- `SyncAnswerGenerationFunction` (`POST /answer-user-query`) — embeds the query (`EmbedDataService`), retrieves chunks (`AzureAISearchService`), calls Azure OpenAI via `ResponseGenerationService`/`ChatService`, and returns the answer in the HTTP response. Also enqueues a scoring message to `STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING`.

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

## CI, Release & Deployment Pipelines

Full write-up with the per-repo details: README.md "Build, Release & Deployment Pipelines".
Summary of the four Azure DevOps pipelines (none run from a local machine):

1. **Build & release — this repo, `azure-pipelines.yaml`** (steps come from `cpp-azure-devops-templates`). SonarQube project key: `uk.gov.moj.cp.azure.ragservice:cp-ai-rag-service`.
   - **PR** → `pipelines/context-verify.yaml`: `mvn verify sonar:sonar` **with `-P ai-rag-integration-test`** — the template logs in with the `airag-var` service principal, so the integration suite runs against real dev Azure on every PR (RBAC on the dev resources must be enabled for it to pass). Quality-gate status is posted to the PR.
   - **Merge to `main`** → `pipelines/context-validation.yaml`: the release build. `jgitflow:release-start` → `mvn clean deploy -DskipTests` to Artifactory `repocentral` → Sonar → `jgitflow:release-finish` (merges `dev/release-<ver>` into `dev/release`, tags `v<ver>`, signs and deploys the release artefacts, bumps `main` to the next `-SNAPSHOT`). **Every merge to `main` cuts a release.** The Docker/AKS validation-stack steps are skipped for this repo; a follow-on job builds `ai-rag-migration:<ver>` from `ai-document-migration-tool/Dockerfile` into the nonlive ACR and promotes it to live.
   - **Artefact:** per function module, `maven-assembly-plugin` (`src/main/assembly/zip.xml`) zips the `azure-functions-maven-plugin` staging dir into `<module>-<version>.zip` at `verify`; it is uploaded under `uk/gov/moj/cp/azure/ragservice/<module>/<version>/` and is what the deployment pipeline fetches.
2. **Function app infrastructure — `hmcts/cpp-terraform-functionapp-deployment`** ("CPP Azure FunctionApp Deployment"; params `platform`, `environment`, `functionapp` = tfvars name `ccm01-airag`). Manual plan → approval-gated apply (ADO environment `<platform>_apply`). Creates the resource group, subnets/NSG, runtime storage + content shares, the identity `mi-<env>-ccm01-airag`, App Insights, the five function apps + App Service plans, Event Grid, dashboard, alerts. Its `functionapp_package` is a one-off bootstrap deploy, not the release path.
3. **Release & settings deployment — `hmcts/cpp-functionapp-deployment`** ("CPP Azure FunctionApp Source Deployment"; same params/shape). Per app in `vars/<env>/ccm01-airag.tfvars`: `package_key` + `version` → Artifactory folder → last `.zip` → `az functionapp deployment source config-zip`; settings (plain + Key Vault / HashiCorp Vault lookups) → `az functionapp config appsettings set`. **Promoting a release = bump `version` in the env tfvars, merge, run the pipeline for that env.**
4. **Models & AI infrastructure — `hmcts/cpp-terraform-azurerm-azure-ai-foundry`** ("CPP Terraform Azure AI foundry"; params `platform`, `environment` ∈ dev/ste-01/sit/nft/prp/prx/prd → `vars/<env>.tfvars`). Manual plan → approved apply. Provisions the AI hub/project, AI Services + `model_deployments`, AI Search **and the index** (schema fetched from **this repo** at git tag `ai_search_index_tag`, currently `v17.0.71` everywhere), Document Intelligence, the RAG data storage account `sa<env>01airag` (containers + lifecycle purge rules), the function-app identity's storage role assignments, and the Key Vault endpoint secrets the app settings look up. **A schema change in `vector-db-index-schema.json` does nothing until that tag is bumped and applied**, and on a populated index it means a new index + the migration tool, not an in-place update.

New-environment order: 2 (creates the identity) → 4 (grants it roles, writes endpoint secrets) → 3 (reads those secrets). Routine code releases use pipeline 3 alone.

## Related Repositories

Only the function code lives here. Before answering "where is X configured", check whether X
belongs to one of these repos instead:

- **`hmcts/api-cp-ai-rag`** — the OpenAPI contract (see "API Contract" above). Spec first, then models, then code.
- **`hmcts/cpp-terraform-azurerm-azure-ai-foundry`** — Terraform for the Foundry estate: AI Services account, AI Search **and the search index** (created from this repo's `vector-db-index-schema.json` at the git tag `ai_search_index_tag`), Document Intelligence, the RAG data storage account `sa<env>01airag` and the function-app identity's role assignments on it, and the **model deployments**. `vars/<env>.tfvars` → `model_deployments.<deployment-name>` sets `model_name`, `version`, `sku_name`, `capacity` and `rai_policy_name`. **`capacity` is the tokens-per-minute rate limit in thousands** (1 unit = 1K TPM; the value shown in the Foundry portal is `capacity × 1000`). Nonlive (dev/sit/nft/ste) and live (prp/prx/prd) run through separate pipelines; the live environments share a single regional quota pool per model + SKU (e.g. Standard gpt-4o in UK South), so raising one environment's capacity may require trimming another's. Neither this repo nor `cpp-functionapp-deployment` sets model capacity.
- **`hmcts/cpp-module-terraform-azurerm-azure-ai-foundry`** — the module the above consumes; `ai-model.tf` maps each `model_deployments` entry onto an `azurerm_cognitive_deployment` (`sku { name, capacity }`).
- **`hmcts/cpp-terraform-functionapp-deployment`** — Terraform for the **function app infrastructure** (`vars/<env>/ccm01-airag.tfvars`): resource group, subnets/NSG, runtime storage, the user-assigned identity, App Insights, the five function apps and their App Service plans, Event Grid, dashboard, alerts. Go here to add an app, resize a plan, or change networking/identity — not for releases.
- **`hmcts/cpp-functionapp-deployment`** — per-environment **app settings and deployed release version** for each function app (`vars/<env>/ccm01-airag.tfvars`, one block per app). This is where env vars documented above (retries, timeouts, provider toggles, feature flags, deployment names, `AzureFunctionsJobHost__*` host overrides) get their production values. The release `version` per app tells you which code is live — check it before assuming a default documented here (e.g. the `openai` provider default) is what production runs. The App Service plans and function app resources are **not** defined here (see the repo above); only their settings and the deployed zip are.
- **`hmcts/cpp-azure-api-management`** — APIM policies fronting the HTTP functions, including client-identity header injection (DD-42722 coordination dependency).
- **`hmcts/cpp-azure-devops-templates`** — shared pipeline templates consumed by `azure-pipelines.yaml`.

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
  `ci-orchestrator`, and `architecture-designer` — rewritten for Functions. Note
  `ci-orchestrator` is **monitor + triage only** (read-only): CI auto-triggers on
  PR, so it observes and triages the existing run, it never triggers a build.
  `architecture-designer` replaces the plugin's CQRS/Spring Boot design flow with
  a Functions-aware one (trigger/binding choice, sync-vs-async, contract-first
  OpenAPI gate, storage/queue state, idempotency & retrieval invariants).
- **Out of scope locally:** CI is **never triggered from a local machine** — it runs
  automatically on PR. The local agents therefore cover implementation, doc
  generation, and CI triage only.
- **Reuse from the plugin as-is:** `requirements-analyst`,
  `story-writer`, `test-engineer`, `research`, `test-analyzer`, `code-reviewer`
  (skip its Spring Boot template-alignment / actuator checks), `api-contract-check`,
  the security hooks (`block-secrets`, `block-pii`, `guard-bash`, `guard-paths`).
- **Do NOT use:** `springboot-service-from-template`, `springboot-api-from-template`,
  `context-scaffold`, `context-service-guide`, `helm-config-validator`,
  `terraform-validate` — no equivalent here.
- Pipeline artefacts still go to `docs/pipeline/` per the plugin convention.
