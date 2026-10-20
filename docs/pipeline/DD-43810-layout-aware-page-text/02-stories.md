# DD-43810 — Story: layout-aware page text at ingestion

## Story
**As a** user asking questions about ingested documents that contain multi-column layouts (e.g. tables or listings without ruled borders),
**I want** each page's text to keep its row and column structure when it is chunked,
**so that** the LLM sees each entry next to its own details, and stops reporting that information is missing or combining separate rows into entries that do not exist.

## Acceptance criteria (from 01-requirements.md)
- **AC-1** Toggle unset or `false`: chunk text is byte-identical to today's single-space join.
- **AC-2** Toggle on: a next line on the same row (|midY diff| <= 0.5 x page median line height) and to the right (next.minX > prev.maxX) is joined with `\t`.
- **AC-3** Toggle on: a line that starts a new row, or sits left of / overlaps the previous line, is preceded by `\n`.
- **AC-4** Toggle on: a null or incomplete polygon on either side gives `\n`, with no exception.
- **AC-5** Toggle on: replacing tabs/newlines with single spaces gives the toggle-off text (no reorder, no loss).
- **AC-6** Toggle on: a two-column fixture keeps each left-hand description line next to its right-hand detail on tab-delimited rows.
- **AC-7** Page numbers and chunk metadata match the toggle-off run (chunk boundaries may shift).
- **AC-8** `mvn verify -pl ai-document-ingestion-function` passes with the new unit tests.

## Definition of Done
1. Code on `dev/feature-DD-43810-layout-aware-page-text`, commits `DD-43810: <summary>`, PR to `main` with `**Jira:** DD-43810` in the description.
2. New unit tests cover AC-1 to AC-7; `mvn verify` passes locally and the module's JaCoCo coverage gate holds.
3. Azure DevOps PR build (`context-verify`, `mvn verify sonar:sonar`) is green and the SonarQube quality gate passes on new code.
4. `INGESTION_LAYOUT_AWARE_TEXT_ENABLED` (default `false`) is documented in `ai-document-ingestion-function/Azure/local.settings.sample.json` and the root `CLAUDE.md`.
5. Code review approved, with no OpenAPI, search-index schema or splitter-config changes in the diff.
6. Follow-up recorded: enable on ste via `cpp-functionapp-deployment`, re-ingest the ragEval evaluation documents and compare results.
