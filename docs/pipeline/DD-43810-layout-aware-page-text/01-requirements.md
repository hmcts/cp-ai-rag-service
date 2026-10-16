# DD-43810 — Requirements: layout-aware page text at ingestion

## Purpose
`DocumentChunkingService.processPage` joins every Document Intelligence (`prebuilt-layout`) line on a page with a single space, so all line breaks and column gaps are lost.
On two-column pages, such as PNC printouts (an external format we cannot change), the offence and sentence columns merge into one run-on string.
The LLM then reports "no relevant previous convictions" or invents offences built from merged rows.
DI already returns one line per column fragment with a bounding polygon; this change keeps DI's line order and only picks a layout-aware separator (tab or newline) between lines.

## Functional requirements
- **FR-1** Between consecutive lines, emit TAB when the next line is on the same row (|vertical-midpoint difference| <= 0.5 x the page's median line height) and to the right (next.minX > previous.maxX); otherwise emit NEWLINE.
- **FR-2** Keep DI's line order exactly; never reorder, drop or alter line text.
- **FR-3** If either line in a pair has no usable polygon, use NEWLINE between them.
- **FR-4** Gate the behaviour behind `INGESTION_LAYOUT_AWARE_TEXT_ENABLED`, read via `EnvVarUtil`, default OFF; when OFF the page text is byte-identical to today's single-space join.
- **FR-5** Document the toggle in `ai-document-ingestion-function/Azure/local.settings.sample.json` and the root `CLAUDE.md`.
- **FR-6** Leave splitter size/overlap, minimum chunk length, page numbering, chunk index and chunk metadata unchanged.

## Non-functional requirements
- **NFR-1** Domain-agnostic: decisions use line geometry only, with no content or keyword rules (e.g. no "PNCID" checks).
- **NFR-2** No OpenAPI contract change and no search-index schema change.
- **NFR-3** Only documents ingested after enablement are affected; existing indexed chunks stay as they are until re-ingested.
- **NFR-4** Unit tests cover same-row tab, new-row newline, missing-polygon fallback, toggle off/on and empty/null lines; the module's coverage gate still passes.

## Out of scope
- Model change (gpt-5.1 / Sonnet) and prompt tuning.
- Vision-based transcription and XY-cut geometric re-ordering (variant A).
- Re-ingesting existing documents.
- App-setting rollout in `cpp-functionapp-deployment` (follow-up).

## Acceptance criteria
- **AC-1** With the toggle unset or `false`, chunk text for any `AnalyzeResult` equals the current single-space-joined output.
- **AC-2** With the toggle ON, two lines whose midpoints differ by <= 0.5 x median line height, with next.minX > previous.maxX, are joined by `\t`.
- **AC-3** With the toggle ON, a line that starts a new row, or sits to the left of / overlaps the previous line, is preceded by `\n`.
- **AC-4** With the toggle ON, a line with a null or incomplete polygon is joined to its neighbours by `\n`, and no exception is thrown.
- **AC-5** With the toggle ON, the page text with tabs/newlines replaced by single spaces equals the toggle-OFF text (no reorder, no loss).
- **AC-6** With the toggle ON, a two-column fixture modelled on a PNC printout keeps each offence and its sentence on separate tab-delimited rows.
- **AC-7** Page numbers and chunk metadata for a fixture document match the toggle-OFF run (chunk boundaries may shift, as the splitter prefers newlines).
- **AC-8** `mvn verify -pl ai-document-ingestion-function` passes with the new unit tests included.
