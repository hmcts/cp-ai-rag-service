# ai-document-migration-tool

A one-off, resumable **index-to-index migration tool** for rebuilding the Azure AI Search index behind
the RAG service with a corrected (v2) schema, copying all existing data across with **no re-embedding**.

This module is **not deployed** — it is an operational utility run by hand when the search index schema
needs to change.

---

## What it's for

Azure AI Search field attributes such as `searchable`, `filterable`, `sortable`, `facetable`, `stored`
and `type` are **immutable once an index contains data** — you cannot `ALTER` them in place — and the
service has **no native index copy/backup** feature. The only supported way to change those attributes on
a populated index is to **stand up a new index with the corrected schema and re-populate it**.

This tool does exactly that, end to end:

1. **Creates** the target index (`ai-rag-service-index-v2`) from the v2 schema
   (`ai-document-shared-artefacts/src/main/resources/vector-db-index-schema-v2.json`).
2. **Copies** every document from the source index to the target — vectors included, so there is **no
   re-embedding** (all fields are `retrievable`, so the stored vectors are read back and re-uploaded as-is).
3. **Verifies** the source and target document counts match.
4. **Prints the alias cutover command** for a zero-downtime switch (see [How to run](#how-to-run)).

---

## Background & rationale

The live index (`ai-rag-service-index`) was created with attribute flags that don't match how the service
actually queries it — e.g. identifier/URL fields were `searchable` (so a free-text query could match on a
filename or GUID and pollute relevance), and every field was `sortable`/`facetable` despite nothing ever
sorting or faceting. Correcting these requires a schema change, which on a populated index means a rebuild.

Because the rebuild has to move a large volume of high-dimensional vectors (3072-float embeddings) over the
network, a naive sequential copy is slow. The tool therefore evolved to be **fast, resilient, and safe**:

- **Keyset pagination** on the key (`orderby id asc` + `id gt '<cursor>'`) instead of `$skip`, which Azure
  caps at 100,000 documents.
- **Parallel sharded reads** — with `workers > 1` the UUID key space is split into 16 shards by leading hex
  character and read concurrently, because the copy is dominated by per-page network round-trips while the
  client sits idle.
- A shared, thread-safe **buffered sender** that auto-batches, splits over-16 MB batches, and retries
  throttling (429/503) — transient errors (e.g. `Connection reset` over a VPN) do **not** abort the run.
- A **no-progress watchdog** so a genuinely hung request (e.g. a half-open socket) can never wedge the run
  indefinitely — it aborts with a clear, re-runnable message instead.
- A **`maxRecords` cap** to copy a small sample for validation before committing to a full run.

> Throughput note: the copy is network-bound. Over a healthy/in-region path a full copy of ~340k documents
> runs in the ~15-minute ballpark; over a degraded VPN/public-internet path it is slower and more prone to
> connection resets. Running the tool **in the same Azure region** as the search service is the single
> biggest lever for speed and stability.

---

## What has changed (v1 → v2 schema)

The migration's purpose is to apply the v2 schema. **Document data is copied verbatim — only the index
field _attributes_ change.** The changes are "hygiene" (removing unused/ harmful flags) plus making
`documentId` directly filterable. See the [field table](#fields-migrated) below for the per-field detail.

Summary of the v2 changes:

- **Only `chunk` stays `searchable`** — identifier/URL/metadata fields are no longer lexically searched, so
  keyword queries can't match on a filename, URL or GUID.
- **`sortable` / `facetable` removed everywhere** they were unused (`id` keeps `sortable` because the
  migration's keyset pagination orders by it).
- **`documentId` is now `filterable`** (forward-looking enablement; the application filter code is unchanged).
- **`synonymMaps` removed** from non-searchable fields.
- **`chunkVector`, `retrievable`, `stored`, `dimensions`, vector/semantic/similarity config are unchanged.**

The tool also encodes several operational decisions:

- **Alias-based cutover.** The pinned `azure-search-documents` Java SDK has **no index-alias API**, so the
  tool prints an `az rest` command (the data-plane REST API does support aliases) rather than calling the SDK.
- **`SearchIndex` is immutable** (no `setName`), so the tool rewrites the schema JSON's `name` to the target
  before parsing — every other property is preserved verbatim from the schema file.
- **Sample runs are quarantined.** A `maxRecords`-capped run skips the full-count verification and does
  **not** emit the cutover command, and logs that the target is a SAMPLE not to be promoted.

---

## How to run

### Prerequisites

- Authenticated via `DefaultAzureCredential` — locally this means `az login` (the tool logs which credential
  it used). The principal needs **Search Service Contributor** (create index / read counts) and **Search
  Index Data Contributor** (read source docs, write target docs).
- HTTP/retry behaviour is configurable via the usual `AZURE_CLIENT_*` / `HTTP_CLIENT_*` env vars (defaults
  are sensible; the Netty response timeout defaults to 180s).

### Command

```bash
mvn -pl ai-document-migration-tool exec:java \
  -Dexec.args="<endpoint> <sourceIndex> <targetIndex> <aliasName> <schemaResourcePath> [workers] [maxRecords] [startAfterId]"
```

```bash
# Full copy, 8 concurrent shards
mvn -pl ai-document-migration-tool exec:java \
  -Dexec.args="https://my-svc.search.windows.net ai-rag-service-index ai-rag-service-index-v2 ai-rag-service-index-alias /vector-db-index-schema-v2.json 8"

# Sample copy of the first 20,000 records (for validation) — no verification, no cutover command
mvn -pl ai-document-migration-tool exec:java \
  -Dexec.args="https://my-svc.search.windows.net ai-rag-service-index ai-rag-service-index-v2 ai-rag-service-index-alias /vector-db-index-schema-v2.json 8 20000"
```

### Arguments

| # | Argument | Required | Default | Description |
|---|----------|----------|---------|-------------|
| 1 | `endpoint` | yes | — | Search service endpoint, e.g. `https://my-svc.search.windows.net` |
| 2 | `sourceIndex` | yes | — | Existing index to copy **from** (e.g. `ai-rag-service-index`) |
| 3 | `targetIndex` | yes | — | New index to create and copy **to** (e.g. `ai-rag-service-index-v2`) |
| 4 | `aliasName` | yes | — | Alias name used in the printed cutover command (e.g. `ai-rag-service-index-alias`) |
| 5 | `schemaResourcePath` | yes | — | Classpath path to the v2 schema (`/vector-db-index-schema-v2.json`) |
| 6 | `workers` | no | `8` | Concurrent shard readers; effective parallelism is `min(workers, 16)` |
| 7 | `maxRecords` | no | `0` (all) | Global cap on documents copied — a positive value makes it a **sample** run |
| 8 | `startAfterId` | no | — | Resume cursor (single-worker runs only; ignored when `workers > 1`) |

### Cutover

A successful **full** run prints a ready-to-run `az rest` `PUT` that points the alias at the new index.
After running it (allow ~10s to propagate and validating), set `AZURE_SEARCH_SERVICE_INDEX_NAME` to the
**alias** in each environment's function settings. Future rebuilds become "build vN+1, repoint the alias"
with no application change. Re-runs are safe — uploads are idempotent upserts keyed by `id`.

---

## Fields migrated

Every field is **copied as-is** (values unchanged); the table shows the **attribute changes** applied by the
v2 schema. `retrievable` and `stored` remain `true` on all fields (required so the copy can read every field,
including the vector, back out). ✅ = enabled in v2, ❌ = disabled in v2; **bold** marks a change from v1.

| Field | Type | searchable | filterable | sortable | facetable | What changed & why |
|-------|------|:----------:|:----------:|:--------:|:---------:|--------------------|
| `id` (key) | `Edm.String` | ❌ | ✅ | ✅ | **❌** | Dropped unused `facetable`. Kept `filterable`+`sortable` — the migration keyset-paginates on `id`. |
| `chunk` | `Edm.String` | ✅ | **❌** | **❌** | **❌** | The only lexically-searched field. Dropped unused filter/sort/facet on this large text body. |
| `chunkVector` | `Collection(Edm.Single)` (3072) | ✅ | ❌ | ❌ | ❌ | **Unchanged.** Vector field; `retrievable`/`stored` true so it copies without re-embedding. |
| `documentFileName` | `Edm.String` | **❌** | ❌ | **❌** | **❌** | No longer lexically searched (identifier, not prose); dropped unused sort/facet. |
| `documentId` | `Edm.String` | **❌** | **✅** | **❌** | **❌** | Now directly `filterable` (enablement); no longer `searchable`; dropped unused sort/facet. |
| `pageNumber` | `Edm.Int32` | ❌ | **❌** | **❌** | **❌** | Retrieved only; dropped unused filter/sort/facet. |
| `chunkIndex` | `Edm.Int32` | ❌ | **❌** | **❌** | **❌** | Retrieved only; dropped unused filter/sort/facet. |
| `documentFileUrl` | `Edm.String` | **❌** | **❌** | **❌** | **❌** | Citation field, retrieved only; dropped searchable/filter/sort/facet. |
| `customMetadata/key` | `Edm.String` (complex) | **❌** | ✅ | ❌ | **❌** | Kept `filterable` (drives the `/any(...)` metadata filters); dropped searchable/facetable. |
| `customMetadata/value` | `Edm.String` (complex) | **❌** | ✅ | ❌ | **❌** | Kept `filterable` (drives the `/any(...)` metadata filters); dropped searchable/facetable. |

> `synonymMaps: []` was also removed from every field except `chunk` (it only applies to searchable string
> fields). Vector search (HNSW), semantic, and similarity (BM25) configuration are carried over unchanged.

---

## Design / code layout

| Class | Responsibility |
|-------|----------------|
| `IndexMigrationTool` | Entry point (`main`): parses args and wires the pieces together |
| `IndexCopier` | The parallel copy engine: keyset pagination, the record cap, the stall watchdog, ETA |
| `Shard` | A key-space slice: partitioning (`plan`) and OData range/keyset filter construction |
| `SearchIndexAdmin` | Client/sender construction, target-index creation, count verification, cutover command |

### Build & test

```bash
mvn -pl ai-document-migration-tool -am clean test
```
