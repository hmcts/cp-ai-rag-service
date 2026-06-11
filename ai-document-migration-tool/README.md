# ai-document-migration-tool

A one-off, resumable **index-to-index migration tool** for rebuilding the Azure AI Search index behind
the RAG service with a corrected schema, copying all existing data across with **no re-embedding**.

This module is **not deployed** — it is an operational utility run by hand when the search index schema
needs to change.

> The specific schema changes this migration applies (and why) are documented separately in
> **[SCHEMA_CHANGES.md](SCHEMA_CHANGES.md)**. This README focuses on how the tool works and how to run it.

---

## What it does

Azure AI Search field attributes (`searchable`, `filterable`, `sortable`, `facetable`, `stored`, `type`) are
**immutable once an index contains data**, and the service has **no native index copy/backup**. The only
supported way to change them is to stand up a new index with the corrected schema and re-populate it. This
tool does that, end to end:

1. **Creates** the target index from the v2 schema
   (`ai-document-shared-artefacts/src/main/resources/vector-db-index-schema-v2.json`).
2. **Copies** every document from the source index to the target — vectors included, so there is **no
   re-embedding** (all fields are `retrievable`, so the stored vectors are read back and re-uploaded as-is).
3. **Verifies** the source and target document counts (see the [verification caveat](#known-limitations--gotchas)).
4. **Prints the alias cutover command** for a zero-downtime switch (see [Cutover](#cutover)).

---

## How it works

The copy is **network-bound** — most wall-clock is per-page round-trips to the service while the client sits
idle — so the engine is built around concurrency, resilience, and bounded resource use:

- **Keyset pagination** on the key (`orderby id asc` + `id gt '<cursor>'`) rather than `$skip` (which Azure
  caps at 100,000 documents). Each page advances a cursor, so it scales to any index size.
- **Sharded parallel reads** — with `workers > 1` the UUID key space is split into **16 shards** by the
  leading hex character of `id`, run concurrently on a fixed thread pool (`min(workers, 16)` at a time). Each
  shard runs its own independent keyset paginator.
- **Pluggable upload path** (`DocumentUploader`) — async (buffered sender) for throughput, or synchronous
  per-page for bounded memory; selected at runtime. See [Upload modes & tuning](#upload-modes--tuning).
- **Non-fatal transient errors** — `429/503` and connection resets are retried/counted, not fatal.
- **No-progress watchdog** — if the aggregate processed count doesn't advance for a few minutes (e.g. a hung
  request on a half-open socket), the run aborts with a clear, re-runnable message instead of wedging.
- **`maxRecords` cap** — copy a small sample first to validate; a capped run skips verification and the
  cutover command, and logs that the target is a SAMPLE not to be promoted.
- **Resume** — with `workers = 1` (a single full-range shard), pass the last logged cursor as `startAfterId`
  to continue an interrupted run. With `workers > 1`, per-shard cursors make a single id meaningless, so it
  is ignored — just re-run (uploads are **idempotent upserts** keyed by `id`).

How it applies the schema:

- **Alias-based cutover.** The pinned `azure-search-documents` Java SDK has **no index-alias API**, so the
  tool prints an `az rest` command (the data-plane REST API does support aliases) rather than calling the SDK.
- **`SearchIndex` is immutable** (no `setName`), so the tool rewrites the schema JSON's `name` to the target
  index before parsing — every other property is preserved verbatim from the schema file.

---

## How to run

### Prerequisites

- Authenticated via `DefaultAzureCredential` — locally this means `az login` (the tool logs which credential
  it used). The principal needs **Search Service Contributor** (create index / read counts) and **Search
  Index Data Contributor** (read source docs, write target docs).
- HTTP/retry and upload behaviour are tuned via env vars (see [Environment variables](#environment-variables));
  defaults are backwards-compatible.

### Command

```bash
mvn -pl ai-document-migration-tool exec:java \
  -Dexec.args="<endpoint> <sourceIndex> <targetIndex> <aliasName> <schemaResourcePath> [workers] [maxRecords] [startAfterId]"
```

```bash
# Full copy, 8 concurrent shards (async, ample memory)
mvn -pl ai-document-migration-tool exec:java \
  -Dexec.args="https://my-svc.search.windows.net ai-rag-service-index ai-rag-service-index-v2 ai-rag-service-index-alias /vector-db-index-schema-v2.json 8"

# Sample copy of the first 20,000 records (validation) — no verification, no cutover command
mvn -pl ai-document-migration-tool exec:java \
  -Dexec.args="https://my-svc.search.windows.net ai-rag-service-index ai-rag-service-index-v2 ai-rag-service-index-alias /vector-db-index-schema-v2.json 8 20000"

# Low-memory / constrained host: sync uploads, 4 workers, heap capped at 1 GB
MAVEN_OPTS="-Xmx1g -XX:+UseG1GC" \
MIGRATION_UPLOAD_MODE=sync \
HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS=15 HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS=10 HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS=30 \
mvn -pl ai-document-migration-tool exec:java \
  -Dexec.args="https://my-svc.search.windows.net ai-rag-service-index ai-rag-service-index-v2 ai-rag-service-index-alias /vector-db-index-schema-v2.json 4"
```

### Arguments

| # | Argument | Required | Default | Description |
|---|----------|----------|---------|-------------|
| 1 | `endpoint` | yes | — | Search service endpoint, e.g. `https://my-svc.search.windows.net` |
| 2 | `sourceIndex` | yes | — | Existing index to copy **from** (e.g. `ai-rag-service-index`) |
| 3 | `targetIndex` | yes | — | New index to create and copy **to** (e.g. `ai-rag-service-index-v2`) |
| 4 | `aliasName` | yes | — | Alias name used in the printed cutover command |
| 5 | `schemaResourcePath` | yes | — | Classpath path to the v2 schema (`/vector-db-index-schema-v2.json`) |
| 6 | `workers` | no | `8` | Concurrent shard readers; effective parallelism is `min(workers, 16)` |
| 7 | `maxRecords` | no | `0` (all) | Global cap on documents copied — a positive value makes it a **sample** run |
| 8 | `startAfterId` | no | — | Resume cursor (single-worker runs only; ignored when `workers > 1`) |

Read page size and the async initial batch size are fixed at **250** (`DEFAULT_PAGE_SIZE`) — a 3072-float
vector serialises to ~25–35 KB of JSON, so 250 keeps a batch (~7–9 MB) safely under Azure's 16 MB request
limit. Don't raise it toward 500+ (risks `413` splits and larger per-request memory).

### Environment variables

| Variable | Default | Effect |
|----------|---------|--------|
| `MIGRATION_UPLOAD_MODE` | `async` | `sync` selects the memory-bounded per-page uploader; anything else = async buffered sender |
| `HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS` | `60` | Bounds a stalled **outbound upload** (the usual connection-straggler cause) |
| `HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS` | `60` | Idle-read timeout — bounds a stalled response |
| `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` | `180` | Overall per-request ceiling |
| `HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS` | `10` | TCP connect timeout |
| `AZURE_CLIENT_MAX_RETRIES` | `3` | SDK retry attempts (keep — retries land a hung flush on a healthy connection) |

> The timeout vars live in the shared `ClientConfiguration` (used by the deployed functions too), but all
> defaults are unchanged, so lowering them only affects a run that explicitly sets them.

### Cutover

A successful **full** run prints a ready-to-run `az rest` `PUT` that points the alias at the new index.
After running it (allow ~10 s to propagate, and validate), set `AZURE_SEARCH_SERVICE_INDEX_NAME` to the
**alias** in each environment's function settings. Future rebuilds become "build vN+1, repoint the alias"
with no application change. Re-runs are safe — uploads are idempotent upserts keyed by `id`.

---

## Upload modes & tuning

The copy is network-bound; tune for **throughput** or **memory** depending on the host.

**`MIGRATION_UPLOAD_MODE=async` (default) — `BufferedSenderUploader`**
Streams batches into a shared buffered sender that auto-batches, splits over-16 MB batches, retries
throttling, and flushes in the background. **Highest throughput**, but it buffers on the producer side: if
flushes lag reads (e.g. during connection stalls), the backlog grows. Needs **ample heap** — under a tight
cap it can `OutOfMemoryError`. Use on a roomy box / in-region.

**`MIGRATION_UPLOAD_MODE=sync` — `SyncUploader`**
Each worker uploads its page and blocks until indexed before reading the next. In-flight memory is bounded
to **`workers × pageSize`** regardless of heap (no backlog), so it runs comfortably in a small heap (a full
~340k copy completes under `-Xmx1g`). **Lower throughput** than async (no read/flush overlap). Use on a
constrained host.

Other knobs:

- **`workers`** — `min(workers, 16)` shards run concurrently. More = faster (up to the service's capacity),
  but more concurrent memory, more load on the service, and a higher chance of a slow connection straggler.
  Use `2–4` on a weak box, `8` otherwise.
- **Heap (`-Xmx`)** — with `async`, give it room; with `sync`, a small cap (e.g. `1g`) is enough. The
  default (25 % of RAM) lets the JVM's RSS balloon on a big-RAM machine even though the live set is small.
- **Timeouts** — over a flaky VPN, lower `HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS` (and read/response) so a
  stalled connection is abandoned and retried in seconds rather than ~minutes.
- **In-region** — removes the VPN/internet path that causes the connection stalls; the biggest single lever.

---

## Known limitations & gotchas

- **Verification is strict and eager.** `verifyCounts` compares `source == target` immediately after the
  copy. This can report a **false mismatch** even when the copy is correct, because (a) Azure's document
  count is **eventually consistent** — the target lags for seconds after the final upload — and (b) a **live
  source drifts** (docs ingested during the run). Re-check `$count` on both indexes after they settle before
  worrying; the target converges to `succeeded`. For a true cutover of a live source, either re-run (cheap —
  idempotent upserts catch the drift) or quiesce source ingestion for a final pass.
- **Connection stragglers over a VPN.** One worker's first upload connection can stall on a half-open socket
  / handshake (tens of seconds up to ~3 min) while the others carry on; it self-recovers and the watchdog
  bounds the worst case. Lower the write/read timeouts to shorten it; run in-region to avoid it.
- **Storage size.** v2 ≈ v1 (the vectors dominate and are unchanged; the dropped auxiliary structures trim
  only a small percentage). A freshly-loaded v2 may show transient bloat from repeated sample/partial
  upserts until Azure's background merge reclaims the superseded versions. See
  [SCHEMA_CHANGES.md](SCHEMA_CHANGES.md#storage-impact).

---

## Design / code layout

| Class | Responsibility |
|-------|----------------|
| `IndexMigrationTool` | Entry point (`main`): parses args, selects the upload mode, wires the pieces together |
| `IndexCopier` | The parallel copy engine: keyset pagination, the record cap, the stall watchdog, ETA |
| `Shard` | A key-space slice: partitioning (`plan`) and OData range/keyset filter construction |
| `SearchIndexAdmin` | Client/sender construction, target-index creation, count verification, cutover command |
| `DocumentUploader` | Upload abstraction — swap throughput for memory without touching the engine |
| `BufferedSenderUploader` | Async path: streams into the shared buffered sender (default) |
| `SyncUploader` | Sync path: per-page upload, bounding in-flight memory to `workers × pageSize` |

### Build & test

```bash
mvn -pl ai-document-migration-tool -am clean test
```

---

## Related docs

- **[SCHEMA_CHANGES.md](SCHEMA_CHANGES.md)** — the v1 → v2 schema changes, per-field detail, and storage impact.
