# ai-document-answer-retrieval-function

Processes user queries end-to-end: embeds the query via Azure OpenAI, retrieves the most relevant document chunks from Azure AI Search (applying a post-retrieval refinement pipeline of containment dedup, semantic dedup, and MMR diversification), generates an LLM answer summary, and persists evaluation artefacts to Blob Storage. Exposes three invocation modes — synchronous HTTP, asynchronous HTTP (initiate + poll), and a queue-triggered async worker — and enqueues a scoring message to `STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING` after every successful answer generation.

## Functions

| `@FunctionName` | Trigger | Route / Queue | Output bindings | Purpose |
|---|---|---|---|---|
| `AnswerRetrieval` | `HttpTrigger` POST | `route = "answer-user-query"` (matches the api-cp-ai-rag contract path) | `QueueOutput` → `%STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING%` | Synchronous: embeds query, searches, generates LLM answer, persists eval payload to Blob, enqueues scoring message, returns answer in HTTP response |
| `InitiateAnswerGeneration` | `HttpTrigger` POST | `answer-user-query-async` | `QueueOutput` → `%STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION%` | Async initiation: validates request, writes `ANSWER_GENERATION_PENDING` row to `STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION`, enqueues `AnswerGenerationQueuePayload`, returns `transactionId` |
| `AnswerGeneration` | `QueueTrigger` | `%STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION%` | `QueueOutput` → `%STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING%` | Async worker: embeds query, searches, generates LLM answer, saves input chunks and eval payload to Blob, updates Table Storage row with result status and duration, enqueues scoring message; retries up to `maxDequeueCount` (default 3, set in `host.json`) before writing a failure status |
| `GetAnswerGeneration` | `HttpTrigger` GET | `answer-user-query-async-status/{transactionId}` | None | Async poll: reads `STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION` by `transactionId`; optionally returns chunked entries from Blob when query param `withChunkedEntries=true` is supplied |

## Post-retrieval refinement pipeline

All three answer-generation paths share the same `AzureAISearchService.search` pipeline. After over-fetching a candidate pool from Azure AI Search, three independently toggled stages run in order before chunks reach the LLM:

1. **Containment dedup** (`ContentContainmentService`) — information-safe; drops a chunk only when its content is already covered by a higher-ranked retained chunk.
2. **Semantic dedup** (`DeduplicationService`) — cosine-similarity dedup; off by default as it is not information-safe.
3. **MMR diversification** (`DiversificationService`) — selects a relevance-vs-diversity balanced subset and truncates to `SEARCH_MMR_FINAL_COUNT`.

The count variables must satisfy `SEARCH_NEAREST_NEIGHBOURS_COUNT >= SEARCH_TOP_RESULTS_COUNT > SEARCH_MMR_FINAL_COUNT`. See the root [CLAUDE.md](../CLAUDE.md) for full sizing guidance and the interaction between these stages.

## Azure dependencies

| Service | Usage |
|---|---|
| Azure OpenAI (embeddings) | `EmbedDataService` calls the deployment named by `AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME` at `AZURE_EMBEDDING_SERVICE_ENDPOINT` to vectorise every user query |
| Azure OpenAI (chat) | `ResponseGenerationService` calls the deployment named by `AZURE_OPENAI_CHAT_DEPLOYMENT_NAME` at `AZURE_OPENAI_ENDPOINT` to generate the LLM answer |
| Azure AI Search | `AzureAISearchService` executes a hybrid (vector + keyword) query against the index named by `AZURE_SEARCH_SERVICE_INDEX_NAME` |
| Azure Queue Storage | Reads from `STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION`; writes to `STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING` |
| Azure Table Storage | `AnswerGenerationTableService` reads and writes async job state in the table named by `STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION` |
| Azure Blob Storage | `BlobPersistenceService` writes eval payloads (container: `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS`) and input-chunk snapshots (container: `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS`) |

## Configuration

| Env var | Purpose | Default |
|---|---|---|
| `AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING` | Name of the identity-based binding used for queue bindings (`connection` attribute on `@QueueTrigger` / `@QueueOutput`); the host resolves `..._CONNECTION_STRING__accountName` and authenticates via managed identity | — |
| `AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT` | Blob Storage endpoint for `BlobPersistenceService` | — |
| `AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT` | Queue Storage endpoint | — |
| `AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT` | Table Storage endpoint for `AnswerGenerationTableService` | — |
| `STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION` | Name of the async answer-generation queue | `answer-generation-queue` (sample) |
| `STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING` | Name of the scoring queue written to after every answer | `answer-scoring-queue` (sample) |
| `STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION` | Name of the Table Storage table for async job state | `answergeneration` (sample) |
| `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS` | Blob container for LLM answer + chunk eval payloads | — |
| `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS` | Blob container for per-transaction input-chunk snapshots read by `GetAnswerGeneration` | <!-- TODO: missing from local.settings.sample.json; add it --> — |
| `AZURE_EMBEDDING_SERVICE_ENDPOINT` | Azure OpenAI endpoint for the embedding model | — |
| `AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME` | Deployment name of the embedding model | — |
| `AZURE_SEARCH_SERVICE_ENDPOINT` | Azure AI Search service endpoint | — |
| `AZURE_SEARCH_SERVICE_INDEX_NAME` | AI Search index name | — |
| `SEARCH_NEAREST_NEIGHBOURS_COUNT` | kNN recall size for vector sub-query (must be >= `SEARCH_TOP_RESULTS_COUNT`) | `50` |
| `SEARCH_TOP_RESULTS_COUNT` | Candidate pool size returned from AI Search (must be > `SEARCH_MMR_FINAL_COUNT`) | `50` |
| `SEARCH_RESULTS_ENABLE_CONTAINMENT_DEDUP` | Toggle information-safe containment dedup (`ContentContainmentService`) | sample: `true` |
| `SEARCH_CONTAINMENT_SHINGLE_SIZE` | Word n-gram size for containment dedup | `3` |
| `SEARCH_CONTAINMENT_THRESHOLD` | Fraction of candidate shingles that must be covered before the chunk is dropped | `0.95` |
| `SEARCH_RESULTS_ENABLE_DEDUPLICATION` | Toggle cosine semantic dedup (`DeduplicationService`) — off by default; not information-safe | `false` |
| `SEARCH_RESULTS_SEMANTIC_DEDUPLICATION_THRESHOLD` | Cosine similarity threshold for semantic dedup | `0.95` |
| `SEARCH_RESULTS_ENABLE_MMR` | Toggle MMR diversification (`DiversificationService`) | sample: `true` |
| `SEARCH_MMR_LAMBDA` | MMR trade-off weight: `1.0` = pure relevance, `0.0` = pure diversity | `0.5` |
| `SEARCH_MMR_FINAL_COUNT` | Number of chunks sent to the LLM after MMR truncation (must be < `SEARCH_TOP_RESULTS_COUNT`) | `15` |
| `AZURE_OPENAI_ENDPOINT` | Azure OpenAI endpoint for the chat model | — |
| `AZURE_OPENAI_CHAT_DEPLOYMENT_NAME` | Deployment name of the chat/LLM model | — |
| `LLM_MODEL_RESPONSE_MAX_TOKENS` | Maximum token budget for the LLM response | `4000` (sample) |
| `RESPONSE_GENERATION_SYSTEM_PROMPT` | System prompt template passed to the chat model | — |
| `AZURE_CLIENT_MAX_RETRIES` | Maximum retries for Azure SDK HTTP client | `3` |
| `AZURE_CLIENT_BASE_DELAY_IN_SECONDS` | Base retry delay | `1` |
| `AZURE_CLIENT_MAX_DELAY_IN_SECONDS` | Maximum retry delay | `60` |
| `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` | Overall HTTP response timeout | `180` |
| `HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS` | HTTP connection timeout | `10` |
| `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` | HTTP read timeout | `60` |
| `AzureFunctionsJobHost__extensions__queues__maxDequeueCount` | Max delivery attempts before `AnswerGeneration` records a failure and stops retrying; must match `host.json` `maxDequeueCount` | `3` |

## Build & run

```bash
# Run unit tests for this module only
mvn test -pl ai-document-answer-retrieval-function

# Package the module (skips tests)
mvn clean package -DskipTests -pl ai-document-answer-retrieval-function

# Run locally (copy Azure/local.settings.sample.json to Azure/local.settings.json and populate values first)
cd ai-document-answer-retrieval-function && mvn azure-functions:run
```
