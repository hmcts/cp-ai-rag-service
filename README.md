# CP AI RAG Service

A multi-module Azure Functions project for AI-powered Retrieval-Augmented Generation (RAG) service.
This service processes documents through ingestion, retrieval, and scoring workflows using Azure AI services.

## Architecture

This mono-repo contains five independently deployable Azure Functions, a shared library, and an integration test module:

### Functions

| Module | Purpose |
|--------|---------|
| `ai-document-metadata-check-function` | Issues SAS upload URLs via HTTP `POST /document-upload`; blob triggers then validate metadata and enqueue files for ingestion |
| `ai-document-ingestion-function` | Orchestrates document preprocessing, chunking, embedding generation, and vector storage |
| `ai-document-answer-retrieval-function` | Processes client queries, performs retrieval/grounding, and generates answer summaries |
| `ai-document-answer-scoring-function` | Scores generated responses and records telemetry in Azure Monitor |
| `ai-document-status-check-function` | HTTP GET endpoints to look up document ingestion status by reference |

### Supporting Modules

| Module | Purpose |
|--------|---------|
| `ai-document-shared-artefacts` | Shared utilities, models (including OpenAPI-generated), entity classes, and Azure service clients |
| `ai-service-orchestration-test` | Integration tests using REST Assured, Testcontainers, and Awaitility |

## Architecture & Data Flow

### Document Ingestion Pipeline

The metadata-check module supports two upload entry flows; both converge on the document-ingestion queue and the same downstream worker.

**Flow A — HTTP-initiated SAS upload** (preferred, two-step):
1. Caller calls `DocumentUploadFunction` (`POST /document-upload`, `@FunctionName("InitiateDocumentUpload")`) with a `DocumentUploadRequest` (documentId, documentName, metadata, overwrites). The function validates the request, rejects duplicates, records an "awaiting upload" row in Table Storage, and returns a write-only SAS URL (issued by `BlobClientService` against the document-upload container) together with the documentId.
2. Caller PUTs the file bytes directly to the returned SAS URL.
3. The blob landing in the upload container fires `DocumentBlobTriggerFunction` (`@FunctionName("DocumentUploadCheck")`), which checks the file size, updates the Table Storage row, and enqueues an ingestion message.

**Flow B — direct blob drop** (file arrives in the primary upload container via an out-of-band mechanism):
1. `BlobTriggerFunction` (`@FunctionName("DocumentMetadataCheck")`) fires; `IngestionOrchestratorService` validates metadata against Table Storage and enqueues an ingestion message on success.

**Downstream (shared by both flows):**
- `DocumentIngestionFunction` (ingestion-function, queue-triggered) consumes the queue and runs `DocumentIngestionOrchestrator`:
  - Azure Document Intelligence extracts text content
  - Content is chunked by `DocumentChunkingService` (uses LangChain4J's recursive `DocumentSplitter`)
  - Embeddings generated via Azure OpenAI
  - Chunks + embeddings stored in Azure AI Search index

### Query & Answer Generation Pipeline

The answer-retrieval module exposes two HTTP invocation modes plus the queue-triggered async worker.

**Synchronous** — single HTTP round-trip:
- `SyncAnswerGenerationFunction` (`POST` `AnswerRetrieval`) — embeds the query (`EmbedDataService`), retrieves chunks (`AzureAISearchService`), calls Azure OpenAI via `ResponseGenerationService`/`ChatService`, and returns the generated answer in the HTTP response. Also enqueues a scoring message to the answer-scoring queue.

**Asynchronous** — request/poll across three functions:
1. `InitiateAnswerGenerationFunction` (`POST /answer-user-query-async`) validates the request, writes a pending row to Table Storage, enqueues a payload to the answer-generation queue, and returns a `transactionId`.
2. `AnswerGenerationFunction` (queue-triggered) consumes the message, runs the same embed → search → LLM flow, persists the result payload to Blob Storage, updates Table Storage status, and enqueues a scoring message.
3. `GetAnswerGenerationResultFunction` (`GET /answer-user-query-async-status/{transactionId}`) is the polling endpoint clients call to retrieve the generated answer once ready.

### Scoring
- `AnswerScoringFunction` evaluates answer groundedness via `ScoringService`
- `PublishScoreService` records metrics to Azure Monitor

## Prerequisites

- Java 21
- Maven 3.3.9 or higher
- Azure Functions Core Tools (for local development)

## Local Development Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd cp-ai-rag-service
   ```

2. **Create local configuration files**
   ```bash
   # Copy sample configuration files for each function
   cp ai-document-metadata-check-function/Azure/local.settings.sample.json ai-document-metadata-check-function/Azure/local.settings.json
   cp ai-document-ingestion-function/Azure/local.settings.sample.json ai-document-ingestion-function/Azure/local.settings.json
   cp ai-document-answer-retrieval-function/Azure/local.settings.sample.json ai-document-answer-retrieval-function/Azure/local.settings.json
   cp ai-document-answer-scoring-function/Azure/local.settings.sample.json ai-document-answer-scoring-function/Azure/local.settings.json
   cp ai-document-status-check-function/Azure/local.settings.sample.json ai-document-status-check-function/Azure/local.settings.json
   ```

3. **Configure your local settings**
Edit each `local.settings.json` file and replace placeholder values with your actual Azure service credentials:
    - Azure Storage connection strings
    - Azure Search endpoint and API key
    - Azure OpenAI endpoint, API key, and deployment name
    - Application Insights instrumentation key
    - Azure Monitor connection string
   

4. **Build the project**
   ```bash
   mvn clean compile
   ```

5. **Run individual functions locally**
   ```bash
   # Navigate to a specific function directory
   cd ai-document-metadata-check-function
   
   # Run the function locally
   mvn azure-functions:run
   ```

## Building and Testing

### Build All Modules
```bash
mvn clean compile
```

### Run Tests
```bash
mvn test
```

### Package for Deployment
```bash
mvn clean package
```

## Deployment

Functions are deployed via Azure Pipelines (see `azure-pipelines.yaml`). Each function is deployed independently to its target environment.

All functions in the same environment share the same resource group.

### Required Azure Resources

Before deployment, ensure the following Azure resources exist for the target environment:
- Azure Storage Account (blob storage, tables, and queues)
- Azure AI Search Service
- Azure OpenAI Service
- Azure Document Intelligence
- Application Insights / Azure Monitor

## Configuration Reference

Each function requires specific environment variables. Refer to each function's `local.settings.sample.json` for the full list. Common variables across all functions:

- `AzureWebJobsStorage` — Azure Storage connection string for the Functions runtime
- `FUNCTIONS_WORKER_RUNTIME` — set to `java`
- `FUNCTIONS_EXTENSION_VERSION` — set to `~4`
- `APPINSIGHTS_INSTRUMENTATIONKEY` — Application Insights instrumentation key

