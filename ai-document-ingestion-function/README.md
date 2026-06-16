# ai-document-ingestion-function

This Azure Function consumes ingestion messages from a Storage Queue and orchestrates the full document processing pipeline: Azure Document Intelligence extracts text from a blob URL, `DocumentChunkingService` splits the content into overlapping chunks via LangChain4J's recursive splitter, `ChunkEmbeddingService` generates vector embeddings in batches via Azure OpenAI, and `DocumentStorageService` uploads the enriched chunks to an Azure AI Search index. On success, any documents superseded by the new version are marked inactive in the index and the outcome is recorded to Azure Table Storage. On terminal failure (dequeue count exhausted), the outcome is recorded as `INGESTION_FAILED` without further retries.

See the root [CLAUDE.md](../CLAUDE.md) for the end-to-end pipeline, queue producers, and the `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION` data-flow description.

## Functions

| @FunctionName | Trigger | Queue | Output bindings | Purpose |
|---|---|---|---|---|
| `DocumentIngestion` | `QueueTrigger` | `%STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION%` (connection: `AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING`) | None (writes to Table Storage and AI Search via SDK calls) | Deserialises a `QueueIngestionMetadata` message, drives Document Intelligence → chunking → embedding → AI Search indexing, marks superseded documents inactive, and records the ingestion outcome in Table Storage |

## Orchestration chain

`DocumentIngestionFunction.run` delegates to `DocumentIngestionOrchestrator.processQueueMessage`, which executes these steps in order:

1. `DocumentIntelligenceService.analyzeDocument` — calls Azure Document Intelligence (`prebuilt-layout` model) with the blob URL from the queue message.
2. `DocumentChunkingService.chunkDocument` — iterates pages from the `AnalyzeResult`, applies LangChain4J `recursive` splitter (default chunk size 4 000 chars, overlap 500 chars), and builds `ChunkedEntry` objects carrying document ID, page number, chunk index, blob URL, and custom metadata.
3. `ChunkEmbeddingService.enrichChunksWithEmbeddings` — submits chunks in batches of `EMBEDDINGS_BATCH_SIZE` to the Azure OpenAI embeddings deployment; each `ChunkedEntry` is updated with a 3 072-dimension `chunkVector`.
4. `DocumentStorageService.uploadChunks` — batch-uploads enriched `ChunkedEntry` records to the AI Search index; chunks with missing or wrongly-sized vectors are skipped with a warning.
5. `DocumentStorageService.markDocumentsInActive` — if Table Storage records a `supersededDocuments` list on the new document, queries AI Search for those document IDs and merges an `isActive=false` flag into their custom metadata.
6. `DocumentIngestionOutcomeTableService.upsertDocument` / `upsertIntoTable` — records `INGESTION_SUCCESS` or, on terminal failure, `INGESTION_FAILED` in `STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME`.

All Azure clients (Document Intelligence, AI Search) authenticate via `DefaultAzureCredential` (Managed Identity).

## Azure dependencies

| Service | Usage |
|---|---|
| Azure Storage Queue | Inbound trigger — reads `QueueIngestionMetadata` messages from `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION` |
| Azure Blob Storage | Source document URL carried in the queue message; the blob is passed by URL to Document Intelligence |
| Azure Document Intelligence | Text extraction via `prebuilt-layout` model (`AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT`) |
| Azure OpenAI (embeddings) | Vector generation for each chunk (`AZURE_EMBEDDING_SERVICE_ENDPOINT` + `AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME`) |
| Azure AI Search | Chunk index — upload, supersession merge, inactive-flag updates (`AZURE_SEARCH_SERVICE_ENDPOINT` + `AZURE_SEARCH_SERVICE_INDEX_NAME`) |
| Azure Table Storage | Ingestion outcome tracking (`STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME`); superseded-document lookup via `AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT` |

## Configuration

All values are read from app settings (or `Azure/local.settings.json` locally). Copy `Azure/local.settings.sample.json` to `Azure/local.settings.json` and populate before running locally.

| Env var | Purpose |
|---|---|
| `AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING` | Name of the identity-based binding used by the `QueueTrigger` (the host resolves `..._CONNECTION_STRING__accountName` and authenticates via managed identity) |
| `AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT` | Blob Storage endpoint (endpoint-based auth) |
| `AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT` | Table Storage endpoint (endpoint-based auth) |
| `AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT` | Queue Storage endpoint (endpoint-based auth) |
| `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION` | Name of the inbound ingestion queue |
| `STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME` | Table name for recording ingestion outcomes |
| `AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT` | Azure Document Intelligence service endpoint |
| `AZURE_SEARCH_SERVICE_ENDPOINT` | Azure AI Search service endpoint |
| `AZURE_SEARCH_SERVICE_INDEX_NAME` | Target AI Search index name |
| `AZURE_EMBEDDING_SERVICE_ENDPOINT` | Azure OpenAI embeddings endpoint |
| `AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME` | Azure OpenAI embeddings deployment name |
| `EMBEDDINGS_BATCH_SIZE` | Number of chunks submitted per embedding API call (default: `2048`) |
| `AzureFunctionsJobHost__extensions__queues__maxDequeueCount` | Max delivery attempts before the message is dead-lettered and the outcome is written as `INGESTION_FAILED`; should match `host.json` (default: `3`) |
| `AZURE_CLIENT_MAX_RETRIES` | Max retries for Azure SDK HTTP calls |
| `AZURE_CLIENT_BASE_DELAY_IN_SECONDS` | Base backoff delay for retries |
| `AZURE_CLIENT_MAX_DELAY_IN_SECONDS` | Maximum backoff delay for retries |
| `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` | Netty HTTP client response timeout |
| `HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS` | Netty HTTP client connect timeout |
| `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` | Netty HTTP client read timeout |

## Build & run

```bash
# Run unit tests for this module only
mvn test -pl ai-document-ingestion-function

# Run a single test class
mvn test -pl ai-document-ingestion-function -Dtest=DocumentIngestionFunctionTest

# Package the module (skips tests)
mvn clean package -DskipTests -pl ai-document-ingestion-function

# Run locally (requires Azure/local.settings.json to be populated)
cd ai-document-ingestion-function && mvn azure-functions:run
```
