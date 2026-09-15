# Input Brief — Migrate off the Azure OpenAI SDK onto the official OpenAI Java SDK

**Source:** Jira DD-43417 + codebase verification (2026-09-15) + stakeholder decisions (Mahesh, 2026-09-15).

**Delivery decision:** staged cut-over behind provider toggles (the established pattern in this repo — cf. `LLM_CHAT_SERVICE_PROVIDER`, `CLIENT_FILTERING_ENABLED`), one Jira subtask + PR per stage. Final removal of the Azure SDK is **deferred** until the OpenAI implementation has been fully exercised in production; until then the `azure` toggle values are the rollback mechanism.

---

## 1. Problem statement

The service calls Azure-hosted OpenAI models through `com.azure:azure-ai-openai` **1.0.0-beta.16** — a perpetually-beta SDK. The official `com.openai:openai-java` SDK (4.41.0, already a dependency) is GA, actively maintained, and supports Azure-hosted deployments via the Azure OpenAI **v1 passthrough surface** (`{endpoint}/openai/v1`) with managed-identity bearer-token authentication.

Goal: all model interactions (chat **and embeddings**) go through the official OpenAI SDK, after which `azure-ai-openai` can be removed. Models stay on Azure OpenAI; auth stays managed identity (`DefaultAzureCredential`) throughout — only the client SDK changes.

## 2. Verified current state (codebase, 2026-09-15)

The migration is already half-done:

- **Chat is provider-switchable.** `ChatServiceFactory` (shared artefacts, `uk.gov.moj.cp.ai.client`) reads `LLM_CHAT_SERVICE_PROVIDER` (default `azure`): `azure` → `AzureChatService` (azure SDK, Chat Completions, `getChatCompletions`), `openai` → `OpenAiChatService` (openai-java, **Responses API**). The OpenAI path is built, unit-tested (12 tests), and evaluated at parity in the offline harness (`ai-document-system-prompt-harness-eval/docs/system-prompt-evaluation-openai-sdk.md` — 80/80, identical citation metrics).
- **`OpenAiClientFactory`** already does bearer auth: `AuthenticationUtil.getBearerTokenSupplier(DefaultAzureCredential, "https://cognitiveservices.azure.com/.default")` → `BearerTokenCredential`, `baseUrl(endpoint + "/openai/v1")`, per-endpoint `ConcurrentHashMap` cache. **It applies no retry or timeout configuration** — SDK defaults only (2 retries, 10-min request timeout).
- **Embeddings are Azure-SDK-only.** `EmbeddingService` (concrete class, no interface) → `AzureOpenAiClientFactory.getInstance(endpoint)` → `client.getEmbeddings(deployment, EmbeddingsOptions)` with `setUser("cp-ai-document-rag-embedding-service")`. Public API: `embedData(String) → List<Float>`, `embedCollectionData(List<String>) → List<List<Float>>`. Consumers: `EmbedDataService` (answer-retrieval) and `ChunkEmbeddingService` (ingestion; caller-side batching via `EMBEDDINGS_BATCH_SIZE`). No openai-java counterpart exists.
- **Azure-path client config** (`ClientConfiguration`): retry `AZURE_CLIENT_MAX_RETRIES`/`AZURE_CLIENT_BASE_DELAY_IN_SECONDS`/`AZURE_CLIENT_MAX_DELAY_IN_SECONDS` + Netty timeouts `HTTP_CLIENT_{RESPONSE,CONNECT,READ,WRITE}_TIMEOUT_IN_SECONDS` (180/10/60/60). Also used by six non-OpenAI Azure clients (Search, Blob×2, Table, Document Intelligence, migration-tool index admin) — **must remain** for those.
- **Azure-only diagnostics in `AzureChatService`**: prompt/content-filter result introspection (`getPromptFilterResults()`/`getContentFilterResults()`) and `CompletionsFinishReason` branching (`CONTENT_FILTERED`, `TOKEN_LIMIT_REACHED`). `OpenAiChatService` uses `response.incompleteDetails()`; the Responses API does not expose Azure content-filter annotations on successful responses.
- **Harness workaround that the migration removes**: `PinnedApiVersionEmbeddingService` (harness) pins Azure api-version `2024-06-01` because hardened Azure OpenAI resources reject the Azure SDK's default preview api-version with a misleading 401. The `/openai/v1` surface is unversioned in that sense — the workaround becomes unnecessary.
- **Tests:** Mockito on SDK classes. Azure-coupled in shared artefacts: `AzureChatServiceTest` (13), `EmbeddingServiceTest` (5 — builds Azure `Embeddings` via the internal `DefaultJsonReader`), `AzureOpenAiClientFactoryTest` (5). OpenAI-side: `OpenAiChatServiceTest` (12, deep-stub pattern), `OpenAiClientFactoryTest` (5), `ChatServiceFactoryTest` (10). Function-module consumer tests mock our own service types.
- **Integration tests** (`ai-service-orchestration-test`, real Azure via locally started function hosts): `RagHarness.setupEnvVarMap()` does **not** forward `LLM_CHAT_SERVICE_PROVIDER` — hosts always run defaults.

## 3. Verified openai-java 4.41.0 SDK surface

- **Embeddings:** `client.embeddings().create(EmbeddingCreateParams.builder().model(deployment).inputOfArrayOfStrings(list).user("…").build())` → `CreateEmbeddingResponse.data()` → `Embedding.embedding()` returns `List<Float>` (drop-in for the current API); `Embedding.index()` for ordering. Full model builders exist for test fixtures. One request per call — caller-side batching unchanged.
- **Client options:** `OpenAIOkHttpClient.builder().maxRetries(int)` (default 2) and `.timeout(com.openai.core.Timeout)` with per-phase connect/read/write/request. Retry: exponential backoff 0.5 s → 8 s cap with jitter; honours `Retry-After`/`Retry-After-Ms`/`X-Should-Retry`; retries 408/409/429/5xx and retryable IOExceptions.
- **Limitation:** backoff base/max are **not configurable** — `AZURE_CLIENT_BASE_DELAY_IN_SECONDS`/`MAX_DELAY` have no equivalent; only max-retries maps. Documented, accepted.

## 4. Stakeholder decisions (2026-09-15)

| # | Decision |
|---|----------|
| D1 | Staged cut-over behind provider flags, mirroring the chat pattern. One Jira subtask + PR per stage under DD-43417. |
| D2 | Embeddings get their own toggle: **`EMBEDDING_SERVICE_PROVIDER`** (default `azure` at introduction), values `azure` \| `openai`. |
| D3 | **Content-filter diagnostics parity must be investigated before the Azure chat path is deleted** (spike): establish what a content-filter trip looks like through openai-java on the v1 surface and add equivalent logging to `OpenAiChatService` if needed. |
| D4 | **Removal stage is deferred** until the OpenAI implementation is fully exercised and confirmed in production. Rollback until then = flip the toggles back to `azure`. |
| D5 | `com.azure:azure-identity` is retained regardless (bearer supplier + all other Azure clients). `ClientConfiguration` is retained for the six non-OpenAI Azure clients. |
| D6 | Jira references in PRs: ticket number `DD-43417` (and subtask refs when provided) in the PR description; no Jira instance access from this environment. |

## 5. Staged scope

1. **Stage 1 — client hardening:** apply retry/timeout config to `OpenAiClientFactory` via a new `OpenAiClientConfiguration` reading the existing env vars (`AZURE_CLIENT_MAX_RETRIES` → `maxRetries`; `HTTP_CLIENT_RESPONSE/CONNECT/READ/WRITE_TIMEOUT_IN_SECONDS` → `Timeout` request/connect/read/write). Benefits the live chat toggle path immediately.
2. **Stage 2 — embeddings toggle (zero behaviour change):** extract `EmbeddingService` interface; current impl becomes `AzureEmbeddingService`; new `OpenAiEmbeddingService` (openai-java embeddings, same semantics incl. `user` tag, empty-data warn, `EmbeddingServiceException` wrapping); new `EmbeddingServiceFactory` on `EMBEDDING_SERVICE_PROVIDER` (default `azure`); wire `EmbedDataService` + `ChunkEmbeddingService` through the factory; forward both provider vars through `RagHarness`; sample-config/docs updates.
3. **Stage 0 spike (parallel):** content-filter parity findings note; gates the deferred removal's chat deletion.
4. **Stage 3 — cut-over:** flip both factory defaults to `openai`; harness moves to `OpenAiEmbeddingService` and `PinnedApiVersionEmbeddingService` is deleted; rollback leg (`…=azure`) proven in integration tests.
5. **Stage 4 — removal (deferred, not scheduled):** delete `AzureChatService`, `AzureEmbeddingService`, `AzureOpenAiClientFactory` + tests; drop `azure-ai-openai` from POMs; factories keep an explicit "provider 'azure' has been removed" error.

## 6. Out of scope

- Moving off Azure-hosted models or changing authentication (managed identity stays).
- Any HTTP contract change (`api-cp-ai-rag` spec untouched — internal plumbing only).
- Prompt/model changes (gpt-5.1 evaluation is a separate workstream).
- Changing retrieval pipeline behaviour (containment dedup / MMR untouched).

## 7. Acceptance criteria (parent level)

- All chat and embedding calls can run through openai-java via configuration, unit + integration tests passing on both toggle positions.
- Retry/timeout behaviour of the OpenAI SDK client matches the documented env-var configuration.
- Rollback to the Azure SDK path is a configuration change only, until the deferred removal completes.
