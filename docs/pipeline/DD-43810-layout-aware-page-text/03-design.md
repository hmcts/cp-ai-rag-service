# DD-43810 — Design: layout-aware page text (variant B)

## Decision
Keep Document Intelligence's line order and only change the separator between consecutive lines: `\t` for same-row-to-the-right, otherwise `\n`.
Geometry only, behind `INGESTION_LAYOUT_AWARE_TEXT_ENABLED` (default `false`). When off, the existing `String.join(" ", ...)` path runs unchanged.

## Change (ai-document-ingestion-function only)
- `DocumentChunkingService` resolves its own toggle, like `DiversificationService` does for `SEARCH_RESULTS_ENABLE_MMR`. The no-arg constructor reads `parseBoolean(getRequiredEnv(INGESTION_LAYOUT_AWARE_TEXT_ENABLED, "false"))`, and the constant is a `public static final String` on the service. A package-private `DocumentChunkingService(boolean)` constructor is the test seam. Callers, including `DocumentIngestionOrchestrator`, are unchanged.
  The constructor logs on construction through the existing slf4j `LOGGER`: `LOGGER.info("Layout-aware page text enabled: {}", layoutAwareTextEnabled)`.
- `extractTextFromPage` returns the filtered `List<DocumentLine>` (same null-line and null-content filter as today). `processPage` then joins with `" "` when off and calls the helper when on.
- New package-private `final class LayoutAwarePageText` (in the same `service` package) with `static String join(List<DocumentLine> lines, Double pageAngle)`; `processPage` passes `page.getAngle()`. It is stateless and needs no env, so it is tested directly.
- `ChunkingConfig` is unchanged. The toggle is a service setting, not a per-call chunking parameter, and adding it to the record would change `getDefault()` and every call site.

## Algorithm (`LayoutAwarePageText.join`)
1. A polygon is usable if it is non-null, has an even size, has at least 8 values (x,y pairs) and every coordinate is finite. From it, bbox = minX/maxX/minY/maxY over the pairs, and midY = (minY+maxY)/2.
2. medianHeight = the median of (maxY-minY) over the page's usable lines. If no line is usable or medianHeight <= 0, every separator is `\n`.
3. Emit lines[0], then for each i>0 emit `sep(prev=lines[i-1], next=lines[i])` followed by lines[i].
4. Page angle: normalised to (-180, 180]; if non-null and |angle| > 45 degrees, every separator is `\n` (null = upright).
5. sep = `\t` if both polygons are usable AND |next.midY - prev.midY| <= 0.5 x medianHeight AND next.minX > prev.maxX. Otherwise sep = `\n`.
6. Line content is appended verbatim. Lines are never reordered, trimmed, dropped or keyword-tested.

## Configuration
`INGESTION_LAYOUT_AWARE_TEXT_ENABLED`: `true`/`false`, default `false`, parsed with `Boolean.parseBoolean`, so anything other than `true` means off. It goes in `ai-document-ingestion-function/Azure/local.settings.sample.json` and in the root `CLAUDE.md` env-var list.

## Test plan (unit, JUnit 5 + Mockito-mocked `DocumentLine`/`DocumentPage`)
| Test | AC |
|---|---|
| `DocumentChunkingServiceTest.shouldSpaceJoinLinesWhenToggleOff` (no-arg and `false` ctor, geometry present) | AC-1 |
| `LayoutAwarePageTextTest.shouldTabJoinSameRowLineToTheRight` | AC-2 |
| `LayoutAwarePageTextTest.shouldNewlineWhenNewRow` / `shouldNewlineWhenSameRowButLeftOfOrOverlapping` | AC-3 |
| `LayoutAwarePageTextTest.shouldNewlineWhenPolygonNullOrFewerThanEightValues` (either side; also no usable polygon on page, empty and single-line input) | AC-4 |
| `LayoutAwarePageTextTest.shouldPreserveOrderAndTextWhenWhitespaceNormalised` | AC-5 |
| `DocumentChunkingServiceLayoutTest.shouldKeepOffenceAndSentenceOnTabDelimitedRows` (two-column PNC-style fixture) | AC-6 |
| `DocumentChunkingServiceLayoutTest.shouldKeepPageNumbersAndMetadataSameAsToggleOff` | AC-7 |
| `mvn verify -pl ai-document-ingestion-function` (JaCoCo gate) | AC-8 |

## Risks & rollout
- Chunk boundaries shift because the recursive splitter prefers `\n`. Offline replay showed chunk counts unchanged on 18 docs. Page numbers and metadata do not change (AC-7).
- `withChunkedEntries` callers will see `\t`/`\n` in `chunkContent`. This is additive whitespace with no schema change; tell consumers.
- Rotated pages (|DI page angle| > 45°) fall back to newline-only separators; small skew only widens the row tolerance and the strict no-overlap test keeps columns apart.
- Mixed corpus: documents ingested before enablement keep their space-joined text until re-ingested.
- Rollout: merge with the toggle off, then (as a follow-up in `cpp-functionapp-deployment`) set it `true` on ste, re-ingest the ragEval bundles and compare against the baseline before promoting.
- Rollback: set the toggle to `false`, a settings-only change with no data migration. Documents already ingested keep their text until re-ingested.

## Not applicable
- Trigger/binding: no new or changed function; this is internal to the existing `DocumentIngestionFunction` queue worker.
- Contract: no HTTP shape change, so `api-cp-ai-rag` is untouched (`chunkContent` is already a free-text string).
- Storage/index/idempotency: no Table, Blob, Queue or `vector-db-index-schema.json` change. `IdempotencyGuard` is unchanged; the change is a pure function of the `AnalyzeResult`.
- Retrieval invariants: kNN >= pool > MMR final are untouched, as is the answer-retrieval module.
