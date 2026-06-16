# ai-document-answer-scoring-function

Queue-triggered Azure Function that evaluates the groundedness of LLM-generated answers. When a scoring message arrives on `STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING`, the function reads the full evaluation payload from Blob Storage, calls a dedicated Judge LLM (`ScoringService`) to produce a 1–5 groundedness score, publishes that score as an OpenTelemetry histogram metric to Azure Monitor (`AzureMonitorService`/`PublishScoreService`), and — if the message originated from an async answer-generation transaction — writes the score back to Table Storage via `AnswerGenerationTableService`.

See the root [CLAUDE.md](../CLAUDE.md) for where this module sits in the end-to-end pipeline (Scoring section) and how upstream modules enqueue messages onto the scoring queue.

## Functions

| @FunctionName | Trigger | Queue | Output bindings | Purpose |
|---|---|---|---|---|
| `AnswerScoring` | `QueueTrigger` | `%STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING%` (connection: `AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING`) | None declared — side-effects via service calls | Deserialises the `ScoringQueuePayload`, reads the `ScoringPayload` blob, calls the Judge LLM for a groundedness score, publishes the score to Azure Monitor, and optionally records it against the transaction row in Table Storage |

## Azure dependencies

| Dependency | Usage |
|---|---|
| Azure Storage Queue | Inbound trigger on the answer-scoring queue (`STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING`) |
| Azure Blob Storage | Reads evaluation payloads from the container named by `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS` (`BlobService` / `BlobClientService`) |
| Azure Table Storage | Writes groundedness score against a transaction row in the table named by `STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION` (`AnswerGenerationTableService`) — only when `transactionId` is present in the payload |
| Azure OpenAI (Judge LLM) | Calls the Judge LLM deployment (`AZURE_JUDGE_OPENAI_ENDPOINT` / `AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME`) via `ChatService` to score answer groundedness on a 1–5 scale |
| Azure Monitor (Application Insights) | Publishes the `ai_rag_response_groundedness_score` histogram metric via OpenTelemetry SDK auto-configured with `RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING` |

## Configuration

| Env var | Purpose |
|---|---|
| `AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING` | Name of the identity-based binding used by the queue trigger (the host resolves `..._CONNECTION_STRING__accountName` and authenticates via managed identity) |
| `AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT` | Blob storage endpoint (used by `BlobClientService`) |
| `AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT` | Queue storage endpoint |
| `STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING` | Name of the inbound scoring queue (default in sample: `answer-scoring-queue`) |
| `STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION` | Table Storage table name for answer-generation rows (default in sample: `answergeneration`) |
| `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS` | Blob container holding serialised `ScoringPayload` files read by `BlobService` <!-- TODO: this var is read by BlobService at construction time but is absent from local.settings.sample.json; add it to the sample file --> |
| `AZURE_JUDGE_OPENAI_ENDPOINT` | Azure OpenAI endpoint for the Judge LLM used by `ScoringService` |
| `AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME` | Deployment name of the Judge LLM chat model |
| `RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING` | Application Insights connection string used by `AzureMonitorService` to export OpenTelemetry metrics. **Intentionally still a connection string** (the only managed-identity exception in this repo): the Azure Monitor exporter has no separate endpoint setter, so the connection string is required as the ingestion endpoint + resource id. Switching to managed-identity (Entra ID) ingestion would need infra changes — disabling local auth on the App Insights resource and granting the function's managed identity the *Monitoring Metrics Publisher* role — so it is not a storage-style `endpoint + credential` cleanup. See `.claude/context/azure-functions.md` → "Known exception". |
| `AZURE_CLIENT_MAX_RETRIES` | Maximum retry attempts for Azure SDK client calls |
| `AZURE_CLIENT_BASE_DELAY_IN_SECONDS` | Base back-off delay for retries |
| `AZURE_CLIENT_MAX_DELAY_IN_SECONDS` | Maximum back-off delay for retries |
| `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` | HTTP response timeout |
| `HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS` | HTTP connect timeout |
| `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` | HTTP read timeout |

## Build & run

```bash
# Run unit tests for this module only
mvn test -pl ai-document-answer-scoring-function

# Run tests with coverage report
mvn verify -pl ai-document-answer-scoring-function

# Package the module (skips tests)
mvn clean package -DskipTests -pl ai-document-answer-scoring-function

# Run locally (copy and populate Azure/local.settings.sample.json → Azure/local.settings.json first)
cd ai-document-answer-scoring-function && mvn azure-functions:run
```
