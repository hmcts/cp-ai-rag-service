# ai-document-metadata-check-function

This module is the document intake gateway for the CP AI RAG service. It exposes an HTTP-initiated SAS-URL upload flow: an HTTP `POST /document-upload` caller receives a write-only SAS URL, uploads the file directly to Blob Storage, which triggers a size-check and then enqueues a `QueueIngestionMetadata` message for the downstream ingestion worker. For the platform-wide architecture and data-flow diagram see the root [CLAUDE.md](../CLAUDE.md).

## Functions

| @FunctionName | Trigger | Route / Queue / Blob path | Output bindings | Purpose |
|---|---|---|---|---|
| `InitiateDocumentUpload` | `HttpTrigger` — `POST` | `document-upload` (auth: `FUNCTION`) | None | Validates the `DocumentUploadRequest`, rejects duplicates, records an `AWAITING_UPLOAD` row in Table Storage, and returns a write-only SAS URL plus the `documentId` for the caller to PUT the file bytes directly to Blob Storage |
| `DocumentUploadCheck` | `BlobTrigger` | `%STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD%/{name}` | `QueueOutput` → `%STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION%` | Fires when the SAS-uploaded file lands; checks blob availability, validates file size against `MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB`, updates Table Storage to `AWAITING_INGESTION` or `FILE_SIZE_OVER_LIMIT`, and enqueues a `QueueIngestionMetadata` JSON message |

## Azure dependencies

| Service | Used by |
|---|---|
| Azure Blob Storage — container `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD` | `DocumentUploadCheck` trigger container; SAS-URL target for `InitiateDocumentUpload` |
| Azure Table Storage — table `STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME` | Document status tracking (`AWAITING_UPLOAD`, `AWAITING_INGESTION`, `FILE_SIZE_OVER_LIMIT`) read and written by both functions via `DocumentIngestionOutcomeTableService` |
| Azure Storage Queue — `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION` | Output queue for `QueueIngestionMetadata` messages consumed by `ai-document-ingestion-function` |

## Configuration

All values are supplied via app settings / `Azure/local.settings.json` (copy from `Azure/local.settings.sample.json`).

| Env var | Purpose |
|---|---|
| `AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING` | Name of the identity-based binding `connection` for all blob triggers and queue output bindings (the host resolves `..._CONNECTION_STRING__accountName` and authenticates via managed identity). The shared `BlobContainerClientFactory` / `TableClientFactory` authenticate separately via the `*_STORAGE_ENDPOINT` vars |
| `AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT` | Blob service endpoint URL; used to construct the `blobUrl` field in the queued `QueueIngestionMetadata` message |
| `AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT` | Table service endpoint URL; `TableClientFactory` authenticates against it via managed identity |
| `AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT` | Queue service endpoint URL; present in `local.settings.sample.json` for completeness — not directly referenced in this module's Java code |
| `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION` | Name of the output queue; referenced in the `@QueueOutput` binding expression on `DocumentUploadCheck` |
| `STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME` | Table name for document ingestion status rows; read by `DocumentUploadService` |
| `STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD` | Blob container for the SAS-upload flow (`DocumentUploadCheck` trigger, SAS-URL generation, and blob-URL construction in `DocumentBlobTriggerFunction`) |
| `SAS_STORAGE_URL_EXPIRY_MINUTES` | SAS URL validity window in minutes; default `120`; read by `DocumentUploadFunction` |
| `UPLOAD_FILE_EXTENSION` | File extension appended when constructing the blob name for a new upload; default `pdf`; read by `DocumentUploadFunction` |
| `UPLOAD_FILE_DATE_FORMAT` | `DateTimeFormatter` pattern used to stamp blob names (e.g. `yyyyMMdd`); default `yyyyMMdd`; read by `DocumentBlobNameResolver` |
| `MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB` | Maximum permitted upload size in MiB; default `80`; read by `DocumentBlobTriggerFunction` |
| `AZURE_CLIENT_MAX_RETRIES` | Maximum retry attempts for Azure SDK clients (shared `ClientConfiguration`); default `3` |
| `AZURE_CLIENT_BASE_DELAY_IN_SECONDS` | Base back-off delay for retries; default `1` |
| `AZURE_CLIENT_MAX_DELAY_IN_SECONDS` | Maximum back-off delay for retries; default `60` |
| `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` | HTTP response timeout for Azure SDK HTTP pipeline; default `180` |
| `HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS` | HTTP connect timeout; default `10` |
| `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` | HTTP read timeout; default `60` |

## Build & run

```bash
# Run unit tests for this module only
mvn test -pl ai-document-metadata-check-function

# Run unit tests with coverage report
mvn verify -pl ai-document-metadata-check-function

# Package the module (skips tests)
mvn clean package -DskipTests -pl ai-document-metadata-check-function

# Run locally (requires Azure/local.settings.json populated from the sample)
cd ai-document-metadata-check-function && mvn azure-functions:run
```
