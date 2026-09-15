# Requirements: Migrate off the Azure OpenAI SDK onto the official OpenAI Java SDK (DD-43417)

## Context
All model interactions in the CP AI RAG Service — chat completion and embeddings — currently reach Azure-hosted OpenAI deployments through `com.azure:azure-ai-openai` **1.0.0-beta.16**, a perpetually-beta SDK. The official `com.openai:openai-java` **4.41.0** SDK is GA, already a declared dependency, and supports Azure-hosted deployments through the Azure OpenAI **v1 passthrough surface** (`{endpoint}/openai/v1`) with managed-identity bearer-token auth. Chat is already provider-switchable (`LLM_CHAT_SERVICE_PROVIDER`, OpenAI path built, unit-tested and evaluated at parity); embeddings are Azure-SDK-only. This initiative completes the migration behind provider toggles, staged one subtask/PR per stage, with removal of the Azure SDK **deferred** until the OpenAI path is exercised in production. Models stay on Azure OpenAI and authentication stays `DefaultAzureCredential` throughout — only the client SDK changes. Source of truth: `docs/pipeline/DD-43417-openai-sdk-migration/00-input-brief.md` (decisions D1–D6).

Not applicable from the generic template: CQRS command/query separation, Spring Boot layering, Helm/actuator concerns — this is a multi-module Maven Azure Functions (Java) service where configuration is function-app environment variables and tests are Maven Surefire unit tests plus the `ai-service-orchestration-test` integration module.

## Actors
| Actor | Description |
|-------|-------------|
| Developer / maintainer | Implements each stage in `ai-document-shared-artefacts` and the consuming function modules; owns unit-test parity for both toggle positions. |
| Release / platform operator | Sets `LLM_CHAT_SERVICE_PROVIDER` and `EMBEDDING_SERVICE_PROVIDER` app settings per environment, runs the manual Azure DevOps deployment pipeline, and executes rollback by flipping toggles back to `azure`. |
| Answer-retrieval function (`ai-document-answer-retrieval-function`) | Consumes embeddings via `EmbedDataService` and chat via `ChatServiceFactory`; latency/quality sensitive. |
| Ingestion function (`ai-document-ingestion-function`) | Consumes embeddings via `ChunkEmbeddingService` with caller-side batching (`EMBEDDINGS_BATCH_SIZE`). |
| Eval-harness operator | Runs `ai-document-system-prompt-harness-eval` offline; today depends on the `PinnedApiVersionEmbeddingService` api-version workaround. |
| Integration-test suite (`ai-service-orchestration-test`) | Starts local function hosts against real Azure; must be able to exercise both provider legs. |
| Technical approver / Jira reporter (DD-43417) | Confirms the deferred-removal gate ("fully exercised in production") and the content-filter parity spike outcome. |

## Functional requirements
| ID | Stage | Requirement | Priority |
|----|-------|-------------|----------|
| FR-1 | Stage 1 | Introduce an `OpenAiClientConfiguration` (shared artefacts, alongside the existing `ClientConfiguration`) that reads the already-documented env vars and produces openai-java client options: `AZURE_CLIENT_MAX_RETRIES` → `maxRetries`, `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` → the `com.openai.core.Timeout` request **and read** phases (OkHttp's read timeout bounds the first-byte wait — the model's processing time on non-streaming calls — so it must carry the response value for Azure parity), `HTTP_CLIENT_CONNECT/WRITE_TIMEOUT_IN_SECONDS` → connect/write. `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` is a documented no-op on this client. *(Read-phase mapping corrected at Stage 1 code review.)* | Must |
| FR-2 | Stage 1 | `OpenAiClientFactory.getInstance(endpoint)` must apply that configuration when building the `OpenAIOkHttpClient`, replacing reliance on SDK defaults (2 retries, 10-minute request timeout). The existing bearer-token supplier (`AuthenticationUtil.getBearerTokenSupplier(DefaultAzureCredential, "https://cognitiveservices.azure.com/.default")`), `baseUrl(endpoint + "/openai/v1")` and per-endpoint `ConcurrentHashMap` cache are preserved unchanged. | Must |
| FR-3 | Stage 1 | Record, in code documentation and `CLAUDE.md`, that openai-java's backoff base/max are **not** configurable (fixed 0.5 s → 8 s exponential with jitter, honouring `Retry-After`/`Retry-After-Ms`/`X-Should-Retry`), so `AZURE_CLIENT_BASE_DELAY_IN_SECONDS` / `AZURE_CLIENT_MAX_DELAY_IN_SECONDS` have no effect on the OpenAI client path. | Must |
| FR-4 | Stage 2 | Extract an `EmbeddingService` **interface** exposing the current public API unchanged — `embedData(String) → List<Float>` and `embedCollectionData(List<String>) → List<List<Float>>`, both declaring `EmbeddingServiceException`. The existing concrete implementation becomes `AzureEmbeddingService` with no behaviour change. | Must |
| FR-5 | Stage 2 | Add `OpenAiEmbeddingService` implementing that interface via openai-java (`client.embeddings().create(EmbeddingCreateParams…inputOfArrayOfStrings(list).model(deployment).user(…))`), reproducing the Azure implementation's observable semantics: the `cp-ai-document-rag-embedding-service` user tag, results ordered to match input order, empty/absent data logged as a warning and returned as an empty list, and all SDK exceptions wrapped in `EmbeddingServiceException`. | Must |
| FR-6 | Stage 2 | Add an `EmbeddingServiceFactory` selecting the implementation from a new env var **`EMBEDDING_SERVICE_PROVIDER`** (values `azure` \| `openai`, default `azure` at introduction), mirroring `ChatServiceFactory`: case/whitespace-insensitive matching, unset → default with an info log, unrecognised value → `IllegalArgumentException` naming the accepted values, provider choice logged at construction. | Must |
| FR-7 | Stage 2 | Wire both embedding consumers — `EmbedDataService` (answer-retrieval) and `ChunkEmbeddingService` (ingestion) — through the factory instead of constructing the concrete service. Caller-side batching (`EMBEDDINGS_BATCH_SIZE`, one request per call) is unchanged. | Must |
| FR-8 | Stage 2 | Forward both `LLM_CHAT_SERVICE_PROVIDER` and `EMBEDDING_SERVICE_PROVIDER` through `RagHarness.setupEnvVarMap()` so integration-test function hosts can be run on either provider leg instead of always running defaults. | Must |
| FR-9 | Stage 2 | Update configuration documentation and samples for the new toggle: `Azure/local.settings.sample.json` for the answer-retrieval and ingestion function modules, the harness `.env.sample`/README, and the "Key environment variables" section of `CLAUDE.md`. | Must |
| FR-10 | Stage 0 (parallel spike) | Produce a content-filter parity findings note recording what an Azure content-filter trip looks like through openai-java on the `/openai/v1` surface (HTTP status/error shape, `response.incompleteDetails()`, whether prompt/content-filter annotations are exposed at all) versus the Azure path's `getPromptFilterResults()` / `getContentFilterResults()` and `CompletionsFinishReason` (`CONTENT_FILTERED`, `TOKEN_LIMIT_REACHED`) branching. (D3) | Must |
| FR-11 | Stage 0 → follow-on | Where the spike identifies a diagnostic gap, add equivalent filter/truncation logging to `OpenAiChatService` so operational triage is no worse than the Azure path. Logged detail must contain no prompt or document content. | Must |
| FR-12 | Stage 3 | Flip both factory defaults to `openai` (`ChatServiceFactory` and `EmbeddingServiceFactory`), so an environment with no provider variables set runs the OpenAI SDK for chat and embeddings. | Must |
| FR-13 | Stage 3 | Move the eval harness to `OpenAiEmbeddingService` and delete `PinnedApiVersionEmbeddingService` — the `/openai/v1` surface is not subject to the Azure SDK preview api-version rejection (misleading 401) that the pin worked around. | Must |
| FR-14 | Stage 3 | Prove the rollback leg: with both variables explicitly set to `azure`, the service must run the Azure SDK path with pre-migration behaviour, verified by integration tests on both toggle positions. Rollback must remain a configuration-only change (no code change, no data change). (D4) | Must |
| FR-15 | Stage 4 (deferred, unscheduled) | Once the OpenAI path is confirmed fully exercised in production **and** FR-10/FR-11 are closed, delete `AzureChatService`, `AzureEmbeddingService`, `AzureOpenAiClientFactory` and their tests, and remove `com.azure:azure-ai-openai` from the root `pom.xml` dependency management and `ai-document-shared-artefacts/pom.xml`. | Should |
| FR-16 | Stage 4 (deferred) | After removal, both factories must fail fast on `provider=azure` with an explicit "provider 'azure' has been removed" error rather than a generic unknown-value message, so a stale app setting is unambiguous at startup. | Should |
| FR-17 | Cross-stage | `com.azure:azure-identity` and `ClientConfiguration` (Netty timeouts + exponential `RetryOptions`) must be retained and left behaviourally unchanged for the bearer-token supplier and the six non-OpenAI Azure clients (AI Search, two Blob clients, Table, Document Intelligence, migration-tool index admin). (D5) | Must |

## Non-functional requirements
| ID | Category | Requirement | Threshold |
|----|----------|-------------|-----------|
| NFR-1 | Functional parity | Embedding vectors produced via the OpenAI SDK must be equivalent to the Azure SDK path for the same input and deployment: same vector dimensionality, same element type (`List<Float>`), same result ordering. | Dimension + ordering identical; no retrieval-quality regression |
| NFR-2 | Reversibility | Rollback from either OpenAI path to the Azure path is achieved solely by setting the provider app setting to `azure` (plus function-app restart). No code change, redeploy of a different artefact, or data rewrite required, until Stage 4 completes. | Config change only |
| NFR-3 | Security | Authentication remains managed identity (`DefaultAzureCredential`) on both paths; no API keys, connection strings or SAS tokens introduced. Bearer tokens must never be logged. | Zero keys; zero token material in logs |
| NFR-4 | Contract stability | No change to the HTTP contract: `api-cp-ai-rag` OpenAPI spec untouched, request/response shapes, status codes and the generated `uk.gov.hmcts.cp.openapi` models unchanged. | Consumer spec unchanged |
| NFR-5 | Resilience | The OpenAI client's retry count and per-phase timeouts are explicitly configured from environment variables (never left at SDK defaults), and the effective request timeout must remain below the Functions host `functionTimeout` so a stalled call fails inside the invocation rather than being killed by the host. | Request timeout < host functionTimeout |
| NFR-6 | Observability | Selected provider is logged once at client/service construction for both chat and embeddings; failures on the OpenAI path are logged with enough detail (status, retry exhaustion, incomplete/truncation reason) to triage without prompt or document content. | No PII/prompt content in logs |
| NFR-7 | Test coverage | Every new class (`OpenAiClientConfiguration`, `OpenAiEmbeddingService`, `EmbeddingServiceFactory`) has Surefire unit tests covering the happy path, empty-data path and exception wrapping; existing suites (`AzureChatServiceTest` 13, `EmbeddingServiceTest` 5, `AzureOpenAiClientFactoryTest` 5, `OpenAiChatServiceTest` 12, `OpenAiClientFactoryTest` 5, `ChatServiceFactoryTest` 10) continue to pass. `mvn verify` green and the SonarQube quality gate met on every stage PR. | All stage PRs green |
| NFR-8 | Performance / cost | No measurable increase in end-to-end answer latency or token consumption attributable to the SDK change; embedding request batching behaviour (one SDK call per batch) unchanged. | No regression beyond normal variance |
| NFR-9 | Maintainability | After cut-over the service depends on a GA SDK for model access; any accepted capability loss (non-configurable backoff, content-filter annotations) is documented rather than silently dropped. | All gaps documented |
| NFR-10 | Backward compatibility | Stage 2 is a zero-behaviour-change refactor when `EMBEDDING_SERVICE_PROVIDER` is unset: consumers, public method signatures and exception types are unchanged. | Byte-for-byte behavioural parity at default |

## Acceptance criteria

### FR-1 / FR-2 / FR-3 — Stage 1: OpenAI client hardening
- AC-1: Given `AZURE_CLIENT_MAX_RETRIES` is set, when `OpenAiClientFactory.getInstance(endpoint)` builds a client, then `maxRetries` on the builder equals that value (unit test asserting the configured value, not the SDK default of 2).
- AC-2: Given `HTTP_CLIENT_RESPONSE/CONNECT/WRITE_TIMEOUT_IN_SECONDS` are set, when the client is built, then the applied `Timeout` carries the response value in both the request and read phases, and the connect/write values in their phases. `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` is never read (see AC-6). *(Corrected at Stage 1 code review.)*
- AC-3: Given none of the retry/timeout variables are set, when the client is built, then the documented repo defaults are applied (not the raw SDK defaults) and the effective values are logged once.
- AC-4: Given two calls to `getInstance` with the same endpoint, when both return, then the same cached `OpenAIClient` instance is returned and only one construction log line is emitted (caching preserved).
- AC-5: Given the factory builds a client, when the credential is inspected, then it is a `BearerTokenCredential` sourced from the shared `DefaultAzureCredential` supplier and the base URL ends with `/openai/v1` (auth and surface unchanged).
- AC-6: Given `AZURE_CLIENT_BASE_DELAY_IN_SECONDS` / `AZURE_CLIENT_MAX_DELAY_IN_SECONDS` / `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` are set, when the OpenAI client is built, then the build succeeds, none of the three is read, and the documentation/Javadoc states these values do not apply to this client (no silent implication of configurability).

### FR-4 / FR-5 — Stage 2: embeddings interface + OpenAI implementation
- AC-7: Given the `EmbeddingService` interface extraction, when the shared artefacts and all function modules are compiled, then no consumer signature changes: `embedData`/`embedCollectionData` return types and `EmbeddingServiceException` are unchanged (compile-time proof plus unchanged consumer tests).
- AC-8: Given a list of N non-empty strings, when `OpenAiEmbeddingService.embedCollectionData` is called, then exactly one SDK `embeddings().create` request is issued and N vectors are returned in input order (ordering asserted via `Embedding.index()`).
- AC-9: Given the SDK returns a response with empty `data()`, when `embedCollectionData` is called, then an empty list is returned and a warning is logged — no exception thrown (matching `AzureEmbeddingService`).
- AC-10: Given the SDK throws any exception, when `embedCollectionData` is called, then an `EmbeddingServiceException` wrapping the cause is thrown (no SDK exception type leaks to callers).
- AC-11: Given a null or empty input list, when `embedCollectionData` is called, then `IllegalArgumentException` is thrown with the existing message (parity with the Azure implementation).
- AC-12: Given a request is built, when its parameters are inspected, then `model` equals the configured deployment name and `user` equals `cp-ai-document-rag-embedding-service`.

### FR-6 / FR-7 — Stage 2: embedding provider factory and wiring
- AC-13: Given `EMBEDDING_SERVICE_PROVIDER` is unset, when `EmbeddingServiceFactory.getInstance` is called, then an `AzureEmbeddingService` is returned and the defaulting is logged (default `azure` at introduction).
- AC-14: Given `EMBEDDING_SERVICE_PROVIDER=openai` (including mixed case / surrounding whitespace), when the factory is called, then an `OpenAiEmbeddingService` is returned.
- AC-15: Given `EMBEDDING_SERVICE_PROVIDER=oepnai` (any unrecognised value), when the factory is called, then an `IllegalArgumentException` is thrown whose message contains the offending value and the accepted values `azure, openai`.
- AC-16: Given `EmbedDataService` and `ChunkEmbeddingService` are constructed, when the provider variable is flipped between `azure` and `openai`, then each obtains its implementation from the factory and no consumer code path is provider-aware.
- AC-17: Given ingestion of a document that produces more chunks than `EMBEDDINGS_BATCH_SIZE`, when it is embedded on the `openai` leg, then the number of SDK calls equals the number of caller-side batches (batching semantics unchanged).

### FR-8 / FR-9 — Stage 2: test harness and configuration surface
- AC-18: Given `LLM_CHAT_SERVICE_PROVIDER` and/or `EMBEDDING_SERVICE_PROVIDER` are exported for an integration-test run, when `RagHarness` starts the function hosts, then both variables are present in the host environment and the hosts run the selected providers (asserted on `setupEnvVarMap()` output).
- AC-19: Given the Stage 2 PR, when `Azure/local.settings.sample.json` for the answer-retrieval and ingestion modules, the harness `.env.sample`/README and `CLAUDE.md` are inspected, then `EMBEDDING_SERVICE_PROVIDER` is documented with its accepted values and default.

### FR-10 / FR-11 — Stage 0: content-filter diagnostics parity spike
- AC-20: Given a prompt/response that trips the Azure content filter on a v1-surface deployment, when the call is made through openai-java, then the findings note records the observed failure shape (exception type, HTTP status, body/`incompleteDetails()` content) and states explicitly whether prompt/content-filter annotations are available on success responses.
- AC-21: Given the findings note, when it is reviewed, then it states a clear verdict — parity achieved, or the specific diagnostic gap and the logging change required in `OpenAiChatService` (FR-11) — and that verdict is recorded as a gate on Stage 4 chat deletion.
- AC-22: Given FR-11 logging is added, when a filtered or truncated response occurs, then the emitted log line identifies the condition without including prompt text, document content or user query content.

### FR-12 / FR-13 / FR-14 — Stage 3: cut-over and proven rollback
- AC-23: Given neither provider variable is set, when the functions start, then `OpenAiChatService` and `OpenAiEmbeddingService` are selected (defaults flipped) and the selection is visible in logs.
- AC-24: Given the harness is run after Stage 3, when the embedding path executes, then it uses `OpenAiEmbeddingService`, `PinnedApiVersionEmbeddingService` no longer exists in the source tree, and no api-version pin remains in harness configuration.
- AC-25: Given both provider variables are set to `azure`, when the full integration suite is run, then the end-to-end ingestion and answer-generation journeys pass with the Azure SDK path (rollback leg green).
- AC-26: Given both provider variables are set to `openai`, when the full integration suite is run, then the same journeys pass with equivalent answers/statuses (forward leg green) — both legs exercised in the same stage.
- AC-27: Given a running environment on the OpenAI path, when an operator changes only the provider app settings to `azure` and restarts the function app, then the service serves traffic on the Azure path with no redeployment of code or data migration.

### FR-15 / FR-16 — Stage 4: deferred removal
- AC-28: Given the deferred-removal gate is satisfied (production exercise confirmed and the FR-10 verdict closed), when Stage 4 lands, then `AzureChatService`, `AzureEmbeddingService`, `AzureOpenAiClientFactory` and their test classes are absent from the tree and `mvn dependency:tree` shows no `com.azure:azure-ai-openai` in any module.
- AC-29: Given a stale `LLM_CHAT_SERVICE_PROVIDER=azure` or `EMBEDDING_SERVICE_PROVIDER=azure` app setting after Stage 4, when the factory is called, then it fails fast with an explicit "provider 'azure' has been removed" message.
- AC-30: Given Stage 4 is complete, when the build is inspected, then `com.azure:azure-identity` and `ClientConfiguration` are still present and the six non-OpenAI Azure clients build and behave unchanged (FR-17 regression check).

## Constraints
- **Staged delivery (D1):** one Jira subtask and one PR per stage under DD-43417; each stage must be independently mergeable and leave the service in a working, releasable state. Jira ticket reference goes in the PR description, not the title.
- **Deferred removal (D4):** the Azure SDK code and dependency must remain until the OpenAI implementation is confirmed fully exercised in production; the `azure` toggle values are the rollback mechanism until then. Stage 4 is not scheduled by this artefact.
- **Content-filter gate (D3):** the chat-side deletion in Stage 4 must not proceed before the Stage 0 parity spike verdict is recorded.
- **Managed identity only** (`.claude/context/azure-functions.md`): all model access uses `DefaultAzureCredential` via the bearer-token supplier; no API keys, connection strings or account keys may be introduced on the OpenAI path.
- **Retained Azure dependencies (D5):** `azure-identity` and `ClientConfiguration` stay regardless of stage; the six non-OpenAI Azure clients must not be touched.
- **Contract-first:** this is internal plumbing only — the `api-cp-ai-rag` OpenAPI spec and the generated models must not change (CLAUDE.md contract-first rule).
- **Azure Functions runtime:** configuration is function-app app settings / `local.settings.json` (git-ignored; only `*.sample.json` committed). No Kubernetes, actuator probes or Spring wiring apply; CI is Azure DevOps on PR and deployment is a separate, manual pipeline after merge.
- **SDK capability limit:** openai-java exposes only `maxRetries` and per-phase `Timeout`; exponential backoff base/max are fixed and not configurable — accepted and must be documented (brief §3).
- **Count-variable and retrieval invariants** (`kNN ≥ pool > MMR final`) and the retrieval refinement pipeline must be untouched by this work.

## Out of scope
- Moving off Azure-hosted models, or any change to authentication (managed identity stays).
- Any HTTP/contract change — `api-cp-ai-rag` spec and generated models untouched.
- Prompt or model changes; the gpt-5.1 evaluation and citation-prompt workstream is separate.
- Changing retrieval pipeline behaviour (containment dedup, semantic dedup, MMR, count variables).
- Changing the Azure SDK clients for AI Search, Blob, Table, Document Intelligence or the migration-tool index admin.
- Scheduling and executing the production soak that satisfies the Stage 4 gate (operational activity, tracked outside this artefact).
- Multi-tenancy / client-identity work (DD-42722) — independent workstream.

## Assumptions
- The Azure OpenAI **v1 passthrough surface** (`{endpoint}/openai/v1`) is available and enabled on the Azure OpenAI resources in every target environment (dev, test, prod), as already demonstrated by the working `OpenAiChatService` path and harness evaluation.
- The function apps' managed identities already hold the role required for the `https://cognitiveservices.azure.com/.default` scope on both chat and embedding deployments (the chat path already uses it; embeddings use the same resource and scope).
- The Azure **deployment name** is accepted as the `model` value on the v1 surface for embeddings, as it is for chat.
- The harness parity evaluation for chat (80/80, identical citation metrics — `system-prompt-evaluation-openai-sdk.md`) is representative of production behaviour; embeddings parity is expected to be exact because `Embedding.embedding()` returns `List<Float>` as a drop-in.
- Reusing the existing `AZURE_CLIENT_*` / `HTTP_CLIENT_*` variable names for the OpenAI client (rather than introducing `OPENAI_`-prefixed ones) is acceptable operationally — see OQ-1.
- Provider app settings can be changed and the function app restarted by the release operator without a redeployment, making rollback a configuration-only action.
- Queue-triggered workers' existing retry/idempotency semantics (visibility timeout, `maxDequeueCount`, lease TTL) remain valid under the new client timeouts — the SDK request timeout stays well inside a single invocation.

## Dependencies
- **`com.openai:openai-java` 4.41.0** — already a declared dependency; no version bump planned within this work.
- **`com.azure:azure-identity`** — retained for the bearer-token supplier and all other Azure clients (D5).
- **Azure OpenAI resource configuration** — v1 surface enabled and RBAC in place per environment; owned by the platform/infra team, required before Stage 3 cut-over in each environment.
- **App-settings delivery** — `EMBEDDING_SERVICE_PROVIDER` (and any changed default) must be reflected in whatever manages function-app settings for each environment, ahead of the Stage 3 deploy.
- **Stage 0 spike output** — gates the Stage 4 chat deletion (FR-10/FR-11 → FR-15).
- **`ai-service-orchestration-test` environment** (`.env`, `az login`, real Azure) — required to prove both toggle legs for FR-14.
- **Jira DD-43417 subtasks** — one per stage; no Jira access from this environment, so subtask creation/linking is a manual step by the reporter (D6).

## Open questions
1. **OQ-1 — env-var naming for the OpenAI client.** Stage 1 reuses `AZURE_CLIENT_MAX_RETRIES` and `HTTP_CLIENT_*_TIMEOUT_IN_SECONDS` for a non-Azure SDK client, which is operationally confusing once the Azure SDK is gone (and couples OpenAI retry tuning to the six other Azure clients). Reuse as briefed, or introduce `OPENAI_CLIENT_*` variables with fallback to the existing names? — Owner: Mahesh — Due: before Stage 1 design sign-off.
2. **OQ-2 — retry-count default divergence.** `ClientConfiguration` defaults `AZURE_CLIENT_MAX_RETRIES` to 3; openai-java defaults to 2. Which default should the OpenAI client adopt when the variable is unset, given the queue-level redelivery budget (`maxDequeueCount` 3) already provides outer retries? — Owner: Mahesh — Due: Stage 1 implementation.
3. **OQ-3 — request-timeout ceiling.** `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` defaults to 180 s; does that (as the openai-java *request* timeout) sit safely inside the Functions host `functionTimeout` and the queue `visibilityTimeout`/lease TTL relationship for the worst-case chat call on a reasoning model? — Owner: TBD — Due: Stage 1 implementation.
4. **OQ-4 — content-filter parity verdict and owner.** Who runs the Stage 0 spike, in which environment (a filter trip must be provoked safely), and where does the findings note live — `docs/pipeline/DD-43417-openai-sdk-migration/` or the harness `docs/`? — Owner: TBD — Due: before Stage 3 cut-over.
5. **OQ-5 — cut-over granularity.** At Stage 3, are chat and embeddings flipped together in one deploy, or embeddings first (chat already exercisable) with a soak between? Does cut-over proceed environment-by-environment with a defined soak period? — Owner: Mahesh/release — Due: before Stage 3.
6. **OQ-6 — definition of "fully exercised in production" (D4).** What concrete evidence closes the deferred-removal gate — elapsed time on the OpenAI path, volume of ingestion/answer transactions, absence of SDK-attributable errors, groundedness scores unchanged? — Owner: Mahesh — Due: before Stage 4 is scheduled.
7. **OQ-7 — Azure-coupled test disposition.** `EmbeddingServiceTest` builds Azure `Embeddings` fixtures via the SDK-internal `DefaultJsonReader`. Should the Stage 2 refactor keep those tests as-is against `AzureEmbeddingService` (deleted at Stage 4), or rewrite them now onto public builders to reduce internal-API coupling? — Owner: developer/reviewer — Due: Stage 2 implementation.
8. **OQ-8 — embedding `user` tag on the v1 surface.** Is the `user` field accepted and equivalently recorded through `/openai/v1`, or does it need dropping/renaming for Azure-hosted deployments? — Owner: developer — Due: Stage 2 implementation.
9. **OQ-9 — integration-test provider matrix cost.** Should CI run the integration suite on both toggle legs at Stage 3 (doubling real-Azure cost/time), or is the second leg a locally run pre-merge gate only? — Owner: Mahesh — Due: before Stage 3.
