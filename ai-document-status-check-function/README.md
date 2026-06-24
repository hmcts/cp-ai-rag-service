# ai-document-status-check-function

Azure Function app that exposes an HTTP GET endpoint for querying the ingestion status of documents that have been submitted through the upload pipeline. It reads rows from an Azure Table Storage table (`STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME`) written by the metadata-check and ingestion functions, and returns a `DocumentIngestionStatusReturnedSuccessfully` or error payload.

This module contains no queue triggers, no output bindings, and no AI service calls. It is a read-only status query layer over Table Storage.

For the full platform architecture, pipeline data flow, and branch/release strategy see the root [CLAUDE.md](../CLAUDE.md).

## Functions

| `@FunctionName` | Trigger | Route / Query | Output bindings | Purpose |
|---|---|---|---|---|
| `DocumentStatusByReference` | `HttpTrigger` GET, `authLevel = FUNCTION` | `route = "document-upload/{documentReference}"` — effective path `api/document-upload/{documentReference}`. Path param: `documentReference` (UUID, bound via `@BindingName`) | None | Validates that `documentReference` is a well-formed UUID, then fetches the Table Storage row by document ID. Returns `400` for an invalid UUID, `404` if no document is found, `200` with `DocumentIngestionStatusReturnedSuccessfully` on success, `500` on any other exception. |

> The `DocumentStatusCheck` lookup-by-`document-name` endpoint (`GET /document-status`) was removed alongside the decommissioned direct-blob-drop (Flow B) ingestion path. Status is now queried by `documentReference` only. Documents are keyed in Table Storage by `documentId`, which `DocumentStatusByReference` resolves directly.

## Azure dependencies

| Service | Usage |
|---|---|
| Azure Table Storage | Read-only. `DocumentIngestionOutcomeTableService` queries the table named by `STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME` via `TableClientFactory`, which connects to the endpoint in `AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT`. |

## Configuration

All variables below are read at function startup. The sample file is at `Azure/local.settings.sample.json`.

| Env var | Purpose |
|---|---|
| `AzureWebJobsStorage` | Azure Storage connection string required by the Functions host runtime |
| `AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT` | Table Storage endpoint URL; used by `TableClientFactory` to construct the `TableServiceClient` |
| `STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME` | Name of the Azure Table that holds ingestion outcome rows |
| `AZURE_CLIENT_MAX_RETRIES` | Maximum retry attempts for Azure SDK client calls (default `3`) |
| `AZURE_CLIENT_BASE_DELAY_IN_SECONDS` | Base back-off delay for retries (default `1`) |
| `AZURE_CLIENT_MAX_DELAY_IN_SECONDS` | Maximum back-off delay for retries (default `60`) |
| `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` | HTTP client response timeout (default `180`) |
| `HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS` | HTTP client connect timeout (default `10`) |
| `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` | HTTP client read timeout (default `60`) |

Cross-reference: the root CLAUDE.md "Key environment variables" section documents the identity-based storage binding (`AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING`) used by other modules' triggers; this module has no storage triggers — it accesses Table Storage directly via the Table Storage endpoint under managed identity.

## Build & run

```bash
# Run unit tests for this module only
mvn test -pl ai-document-status-check-function

# Run tests with coverage
mvn verify -pl ai-document-status-check-function

# Package the module (skips tests)
mvn clean package -DskipTests -pl ai-document-status-check-function

# Run locally (copy Azure/local.settings.sample.json → Azure/local.settings.json and populate values first)
cd ai-document-status-check-function && mvn azure-functions:run
```
