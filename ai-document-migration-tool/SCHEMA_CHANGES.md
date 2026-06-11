# v2 Index Schema Changes

The [migration tool](README.md) rebuilds the Azure AI Search index with a corrected **v2 schema**, defined
in `ai-document-shared-artefacts/src/main/resources/vector-db-index-schema-v2.json`. This document describes
what changed from the original (v1) schema and why.

**Document data is copied verbatim — only the index field _attributes_ change.**

---

## Why the schema changed

The live index (`ai-rag-service-index`) was created with attribute flags that don't match how the service
actually queries it:

- identifier/URL fields were `searchable`, so a free-text query could match on a filename or GUID and
  pollute relevance;
- every field was `sortable`/`facetable` even though nothing ever sorts or facets.

Those attributes (`searchable`, `filterable`, `sortable`, `facetable`, `stored`, `type`) are **immutable
once an index holds data**, and Azure AI Search has **no native copy/backup**. So correcting them means
standing up a new index with the fixed schema and re-populating it — which is exactly what the migration
tool does.

---

## Summary of v2 changes

- **Only `chunk` stays `searchable`** — identifier/URL/metadata fields are no longer lexically searched, so
  keyword queries can't match on a filename, URL or GUID (tighter relevance, smaller inverted indexes).
- **`sortable` / `facetable` removed everywhere** they were unused (`id` keeps `sortable` because the
  migration's keyset pagination orders by it).
- **`documentId` is now `filterable`** — forward-looking enablement; the application's filter code is
  unchanged (it still filters via `customMetadata/any(...)`).
- **`synonymMaps` removed** from every field except `chunk` (they only apply to searchable string fields).
- **Unchanged:** `chunkVector` (incl. `dimensions`), `retrievable`, `stored`, and the vector (HNSW),
  semantic, and similarity (BM25) configuration.

---

## Fields

`retrievable` and `stored` remain `true` on all fields (required so the copy can read every field, including
the vector, back out). ✅ = enabled in v2, ❌ = disabled in v2; **bold** marks a change from v1.

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

---

## Storage impact

Because `chunkVector` (3072-dim, `retrievable`+`stored`) is unchanged and the **vectors dominate storage**,
v2's total size lands **close to v1's** — marginally smaller, as the dropped auxiliary structures (inverted
indexes for the de-`searchable`d fields, plus the sort/facet structures) are trimmed. A freshly-loaded v2
may briefly show extra size from repeated sample/partial upserts until Azure's background merge reclaims the
superseded versions (see the README's "Known limitations & gotchas").
