# Requirements: Validate LLM citations against the retrieved chunks (DD-43809)

## Context
The answer-retrieval function renders every user-visible citation — `::(Source: [<file>], Pages <compressed>|<individual>|documentId=<id>)` — directly from the model's `<FACT_MAP_JSON>` entry, without checking it against the chunks that were actually retrieved for that answer. When the model mistypes a `documentId` (observed on dev01, 2026-10-09: the last two characters of a valid id were dropped), the citation points at no document and the consuming UI shows a broken link. The file name and pages stayed correct, so this was a broken link, not a cross-document leak. This change validates every citation entry against the retrieved chunks, renders only validated citations, and routes "no valid citation left" through the existing citation guard. Source of truth: `00-input-brief.md`; its "Agreed behaviour" section was decided with the product owner on 2026-10-20 and is captured here as fixed requirements.

Not applicable from the generic template: UI/accessibility, Spring Boot layering, actuator, CQRS/event sourcing. This is a multi-module Maven Azure Functions (Java) service; the change is internal post-processing in `ai-document-answer-retrieval-function` plus a small shared-artefacts helper and the offline evaluation harness.

## Actors
| Actor | Description |
|-------|-------------|
| API consumer (calling UI/service) | Receives the formatted answer from `POST /answer-user-query` (sync) or `GET /answer-user-query-async-status/{transactionId}` (async) and renders each `::(Source: …)` citation as a link to a document/page. Harmed by citations pointing at no document. |
| Answer-retrieval function | `ResponseGenerationService` → `CitationProcessor`; owns retrieval, LLM call, citation post-processing and the citation guard on both sync and async paths. |
| Async answer worker (`AnswerGenerationFunction`) | Queue-triggered; retries a citation-degraded answer via queue redelivery (`maxDequeueCount` 3), then applies `CITATION_GUARD_MODE` (deliver/reject); writes the answer-generation table's `reason` column. |
| Downstream quality suite (ragEval, `cpp-ui-e2e-serenity`) | Treats any non-blank `reason` on an `ANSWER_GENERATED` answer as citation-degraded (gate `maxCitationDegradedRate` = 0.0). |
| Evaluation-harness operator (`ai-document-system-prompt-harness-eval`) | Runs prompt × model × query evaluations offline; `CitationMetrics` and `TestHarness` call `CitationProcessor` and report citation metrics. |
| Operator / on-call | Reads Application Insights traces (`txn=` / `inv=` MDC keys) to triage citation quality. |

## Functional requirements
| ID | Requirement | Priority |
|----|-------------|----------|
| FR-1 | **Validation input.** `ResponseGenerationService.generateResponse` must pass the retrieved `chunkedEntries` (the exact list used to build the prompt context) to `CitationProcessor`. No production code path may render a citation without validating it. | Must |
| FR-2 | **documentId validation.** An entry is valid on `documentId` only if its value **exactly** equals (case-sensitive, no trimming, no prefix/edit-distance) the `documentId` of at least one retrieved chunk. Missing, null, blank or non-matching → invalid. | Must |
| FR-3 | **Filename validation.** With a valid `documentId`, the entry's `documentFilename`, trimmed, must match **case-insensitively** the filename the model was shown for **that same document** — the non-empty `material_name` custom-metadata value if present, else `documentFileName` (FR-8). Missing, blank, or non-matching (including another retrieved document's filename) → invalid. | Must |
| FR-4 | **Page validation and trimming.** Each token in `individualPageNumbers` is valid only if it equals a `pageNumber` of a retrieved chunk of that same document. Invalid tokens are **trimmed**; the entry is kept if at least one page survives. No surviving page (incl. missing/empty/`N/A`) → invalid. | Must |
| FR-5 | **Drop invalid entries (no repair).** Invalid entries are removed before merge and substitution; their markers become unresolved and are stripped by the existing logic. Garbled values are never repaired, inferred or substituted. | Must |
| FR-6 | **Remove fallbacks.** `UNKNOWN_ID` and `UNKNOWN_FILE` are removed; a citation renders only with a validated documentId and filename. | Must |
| FR-7 | **Render from retrieval.** A kept citation renders `documentId` and filename **from the retrieved chunks** (FR-8), never the model's copies. Pages render from surviving tokens only; the compressed `Pages` part never references a page outside the surviving set. `CITATION_FORMAT` is unchanged. | Must |
| FR-8 | **Single source for the displayed filename.** The rule selecting a document's displayed filename (first chunk of the document in retrieval order; non-empty `material_name`, else `documentFileName`), currently private in `ChunkFormatterUtility.extractMaterialName`, is exposed as one shared rule used by both the prompt builder and the validator. | Must |
| FR-9 | **Order of operations.** Validation runs after JSON parsing and **before** same-document run merging and substitution; a merged citation includes only valid entries, the union of their *surviving* pages, and retrieval-sourced documentId/filename. | Must |
| FR-10 | **Refusal detection uses the original list.** `CitationOutcome.emptyFactMap` is computed from the original parsed list, so "every entry invalid" is never a deliberate refusal. A genuine `[]` with no markers is still accepted as today. | Must |
| FR-11 | **No valid citation → citation-degraded.** If validation leaves zero rendered citations (and FR-10 refusal does not apply), `isAcceptable` fails and the existing guard path applies unchanged (async redelivery then deliver/reject; sync immediate; `off` delivers with reason). The guard reason gains `invalid=N` (FACT_MAP_JSON entries dropped): `[guard=<mode>] Citations missing: jsonBlock=…, inlineMarkers=…, rendered=…, stripped=…, invalid=N`. | Must |
| FR-12 | **Partial invalidity — log only.** If at least one entry was dropped or page trimmed and at least one citation renders, the answer is `ANSWER_GENERATED` with **no reason** (`LlmResponse.reason` null). Exactly one WARN line per answer, counts only (NFR-4). | Must |
| FR-13 | **Outcome carries the counts.** `CitationOutcome` exposes dropped entries (and trimmed pages), so guard, WARN line and harness read one value. | Must |
| FR-14 | **Harness parity.** `TestHarness` and `CitationMetrics` evaluate citations against the same chunks used for generation; `CitationMetrics.Compliance` reports an invalid-citation count, shown in harness reports. | Must |
| FR-15 | **No chunk-less entry point.** The chunk-less `processCitations(String)` / `processAndFormatCitations(String)` are removed or require the scope. A null/empty scope means every entry is invalid — never "skip validation". | Must |
| FR-16 | **Deterministic duplicates.** Entries sharing a `citationId` (number or string) are validated independently; the **first valid** entry renders at every occurrence of that marker; invalid duplicates are dropped and counted. | Must |
| FR-17 | **Log hygiene.** New/changed log statements in `CitationProcessor` and `ResponseGenerationService` contain no documentId, filename or page content; the existing merge INFO line drops its `documentId={}` argument. | Should |
| FR-18 | **Merge across dropped markers.** In an adjacent run, a dropped marker does not prevent the remaining same-document entries from merging (e.g. `[1][2][3]`, 1 and 3 valid for one document, 2 invalid → one merged citation, `[2]` stripped). | Should |

## Non-functional requirements
| ID | Category | Requirement | Threshold |
|----|----------|-------------|-----------|
| NFR-1 | Contract stability | No HTTP contract change: `api-cp-ai-rag` spec, generated models, status codes, `answerGenerationStatus` and the citation string format unchanged. | Spec untouched |
| NFR-2 | Configuration | No new environment variable; validation always on; `CITATION_GUARD_MODE` semantics unchanged. | Zero new env vars |
| NFR-3 | Determinism & cost | Pure, in-process, deterministic; no extra LLM/embedding/search/storage calls. Extra async retries only via the existing redelivery path for answers now (correctly) degraded. | 0 extra model calls per attempt |
| NFR-4 | Logging | Per `docs/transaction-scoped-logging.md`: SLF4J → log4j2, MDC correlation; no journey IDs, documentIds, filenames, query or answer text in messages. Partial-invalidity WARN carries counts only (entries, dropped, trimmed pages, rendered; optionally by cause). | 0 IDs/names in messages |
| NFR-5 | Performance | Per-document lookups built once per answer; linear in entries + chunks. | < 5 ms per answer at default sizing |
| NFR-6 | Correctness | No rendered citation carries a documentId not among the retrieved chunks, or a page not retrieved for that document. | Zero unvalidated citations |
| NFR-7 | Observability | Total invalidity measurable via the reason column (`invalid=N`); partial invalidity via the WARN line and the harness metric. | Both signals present |
| NFR-8 | Test coverage | Unit tests cover every FR and edge case; existing citation suites (`CitationProcessorTest`, `…MergeTest`, `…DriftTest`, `…Gpt41Test`, `…Gpt51Test`, `CitationOutcomeTest`, `ResponseGenerationServiceTest`, `CitationMetricsTest`) updated to supply chunks and still pass; `mvn verify` green; Sonar gate met. | PR green |
| NFR-9 | Domain neutrality | Domain-neutral wording and fixtures (`report-a.pdf`, `notes-b.pdf`, generated UUIDs). | No domain terms |

## Edge cases (required behaviour)
| # | Case | Required behaviour |
|---|------|--------------------|
| E-1 | Multiple documents in scope | Each entry validated against **its own** document's filename and pages, never the union. |
| E-2 | Same filename on two documents A and B | Entry `{A, "report-a.pdf"}` valid; renders A and only A's pages; a page retrieved only for B is trimmed. |
| E-3 | B's filename with A's documentId | Invalid (filename mismatch for A) → dropped. |
| E-4 | Non-numeric page tokens (`iv`) | Retrieved `pageNumber` is an `Integer`, so never matches → trimmed; sole token → entry dropped. |
| E-5 | Range tokens (`"3-5"`) | Not expanded (no repair) → trimmed. |
| E-6 | Pages as JSON array or comma string | Equivalent: split, trim, ignore empties, compare numerically. |
| E-7 | `citationId` as number or string | Equivalent; both resolve `[1]`. |
| E-8 | Duplicate `citationId`s | FR-16. |
| E-9 | Chunks with null `pageNumber` | Contribute no valid pages; a document whose chunks all have null pages is uncitable. |
| E-10 | Empty/null chunk list | `ResponseGenerationService` already short-circuits before the LLM call; at processor level every entry is invalid (FR-15). |
| E-11 | Merged run with one invalid entry | Invalid entry removed before merge; merged citation holds only surviving pages; dropped marker stripped (FR-18). |
| E-12 | Entry with no `citationId` | Cannot render → dropped and counted in `invalid`. |
| E-13 | Valid entry referenced by no marker | Renders nothing (as today); invalid unreferenced entries still count. |
| E-14 | All entries invalid, no markers | rendered = 0, `emptyFactMap` = false → citation-degraded. |
| E-15 | Model's compressed `pageNumbers` references a trimmed page | Not rendered verbatim; compressed form derived from surviving pages. |
| E-16 | Async retry | Each redelivery re-retrieves; validation uses that attempt's chunks. |
| E-17 | One document, chunks with differing `material_name` | First chunk's value is displayed and validated against (FR-8). |
| E-18 | Harness | `CitationMetrics.compute` must use the run's chunks, otherwise every harness answer would read as fully invalid. |

## Acceptance criteria
Fixtures: document A `id=aaaaaaaa-0000-4000-8000-000000000001`, shown filename `report-a.pdf`, retrieved pages {2, 3}; document B `id=bbbbbbbb-0000-4000-8000-000000000002`, shown filename `notes-b.pdf`, retrieved page {5}.

### FR-1 / FR-15 — Validation input
- AC-1: Given `generateResponse` is called with chunks for A and B, when the answer is post-processed, then `CitationProcessor` receives that same chunk list.
- AC-2: Given a null/empty scope passed directly to the processor, when a raw answer with valid-looking entries is processed, then every entry is invalid, `renderedCitations` = 0 and no `::(Source:` appears.
- AC-3: No production call to `CitationProcessor` without a chunk scope remains (compile-level).

### FR-2 / FR-5 / FR-6 — documentId validation and dropping
- AC-4: Given entry `{citationId:1, documentId:"aaaaaaaa-0000-4000-8000-0000000000", documentFilename:"report-a.pdf", individualPageNumbers:"2"}` and text `Fact one [1].`, then the output has no `[1]` and no `::(Source:`, and `invalid` = 1.
- AC-5: An entry whose documentId differs from A's only by case or surrounding whitespace is dropped.
- AC-6: An entry with no `documentId` is dropped; output contains neither `UNKNOWN_ID` nor `UNKNOWN_FILE`.

### FR-3 — Filename validation
- AC-7: Entry for A with `documentFilename:"  REPORT-A.PDF "` is kept and renders `Source: [report-a.pdf]`.
- AC-8: Entry for A with `documentFilename:"notes-b.pdf"` is dropped.
- AC-9: A's chunks carry `material_name=summary-a` and `documentFileName=report-a.pdf`: `"report-a.pdf"` is dropped; `"summary-a"` is kept and renders `Source: [summary-a]`.
- AC-10: A and B both shown as `report-a.pdf`: `{documentId:A, documentFilename:"report-a.pdf", individualPageNumbers:"2,5"}` is kept, page 5 trimmed, renders `documentId=A` with pages `2`.

### FR-4 — Page trimming
- AC-11: `individualPageNumbers:"2, 7, iv"` for A → kept with pages `2`; trimmed-page count 2.
- AC-12: `[2,3]` and `"2,3"` render identically.
- AC-13: `"7"`, `"N/A"`, `""` or missing → entry dropped.
- AC-14: `"2-3"` → token trimmed, entry dropped.

### FR-7 — Rendering from retrieval
- AC-15: Valid entry for A with model `pageNumbers:"2-7"`, `individualPageNumbers:"2,3,7"` renders exactly `::(Source: [report-a.pdf], Pages 2,3|2,3|documentId=aaaaaaaa-0000-4000-8000-000000000001)`.

### FR-8 — Shared displayed-filename rule
- AC-16: Prompt builder and validator use the same shared method; a test asserts agreement for `material_name` present / blank / absent / differing across chunks (first wins).

### FR-9 / FR-18 — Validation before merge
- AC-17: `Claim [1][2].`, both A (pages 2, 3), 2's documentId garbled → one citation for A with pages `2`, no `[2]`.
- AC-18: `Claim [1][2][3].`, 1 (page 2) and 3 (page 3) valid for A, 2 invalid → single merged citation for A with pages `2,3`, no bare markers.
- AC-19: `Claim [1][2].`, 1 valid for A, 2 valid for B → two separate citations.

### FR-10 / FR-11 — Total invalidity is degradation
- AC-20: Two entries, both invalid, two markers → `emptyFactMap` = false, `renderedCitations` = 0, `invalid` = 2.
- AC-21: Async, `deliver`: `CitationDegradedException` with reason containing `invalid=2`; on exhaustion persisted `ANSWER_GENERATED` with reason starting `[guard=deliver] Citations missing:`.
- AC-22: Sync, `reject`: existing reject behaviour (200 with the failure sentinel, scoring skipped).
- AC-23: `off`: `ANSWER_GENERATED` with reason `[guard=off] Citations missing: … invalid=2`.
- AC-24: `[]` with no markers → accepted refusal, no reason (unchanged).
- AC-25: A degraded answer with nothing dropped (e.g. no JSON block) carries `invalid=0`.

### FR-12 / FR-13 / FR-17 — Partial invalidity
- AC-26: Three entries, one invalid, two render → `ANSWER_GENERATED`, reason null, no exception, exactly one WARN line.
- AC-27: That WARN contains the counts and no documentId, filename, answer or query text.
- AC-28: Only pages trimmed (no entry dropped) → accepted, no reason, WARN reports trimmed pages > 0.
- AC-29: The merge INFO line contains no documentId.

### FR-16 — Duplicates and citationId types
- AC-30: `{citationId:1, documentId:<garbled>}` then `{citationId:"1", documentId:A, …valid}`, text `X [1]. Y [1].` → both markers render A; `invalid` = 1.
- AC-31: Two valid entries with `citationId` 1 (A page 2, B page 5) → every `[1]` renders A.

### E-9 — Null page numbers
- AC-32: B's only chunk has `pageNumber=null` → an entry for B with `"5"` or `"N/A"` is dropped.
- AC-33: A has chunks with pages {2, null} → entry citing `"2"` is kept.

### FR-14 — Harness
- AC-34: A harness run with one invalid and one valid entry reports invalid = 1, rendered = 1 in `CitationMetrics.Compliance` and in the summary/detail output.
- AC-35: A run with only valid citations reports invalid = 0 and `renderedCitations` matching production.

### NFR-1 / NFR-2 / NFR-3
- AC-36: No change to the `api-cp-ai-rag` spec, generated models or env-var keys (documentation of behaviour only).
- AC-37: With a mocked `ChatService`, `callModel` is invoked exactly once; identical inputs give identical output.

## Constraints
- Agreed behaviour (PO, 2026-10-20) is fixed — see `00-input-brief.md`.
- Contract-first: no HTTP contract or citation-format change.
- Logging convention (`docs/transaction-scoped-logging.md`).
- Reason-column semantics: a non-blank reason on `ANSWER_GENERATED` means "citation-degraded" downstream; never set for partial invalidity.
- Idempotency and guard mechanics unchanged (leases, `maxDequeueCount`, release-before-rethrow).
- Retrieval pipeline untouched; validation uses the post-refinement chunk list actually sent to the model.
- Domain-agnostic wording throughout.

## Out of scope
- Repairing garbled ids or filenames.
- The `isAcceptable` empty-`[]` refusal loophole (DD-43838).
- Any API contract change, new status or response field.
- System-prompt changes to reduce id transcription errors (possible follow-up).
- Ingestion, chunking, page numbering, index schema, scoring, ragEval changes; back-filling stored answers.

## Risks
- **R-1 Higher degraded/retry rate:** answers that previously passed with broken citations now fail the guard → more async redeliveries and, under `reject`, more FAILED rows. Failure mode is intermittent; monitor guard WARNs and `invalid=` counts post-release.
- **R-2 Strict page matching:** a model citing an adjacent page loses that page. Chunks are per page (`DocumentChunkingService` sets `pageNumber(pageIndex)`), so risk is low; measure via harness (FR-14) and ragEval.
- **R-3 Filename strictness:** a model that drops an extension or normalises punctuation has its entry dropped — accepted under "no repair"; monitor the WARN breakdown.
- **R-4 Harness offline re-analysis:** persisted `RunResult`s hold only the `LlmResponse`, not chunks (OQ-6).
- **R-5 Rendered filename casing:** valid citations now render the retrieval filename; casing may differ from the model's echo. No contract impact.
- **R-6 Partial invalidity invisible to ragEval** by design; visible only in logs and harness metrics.

## Assumptions
- A-1: `ChunkedEntry.pageNumber` is the integer page index for all chunks ingested by the current pipeline (confirmed: `DocumentChunkingService` always sets it).
- A-2: The chunk list passed to `generateResponse` is exactly the list rendered into `<RETRIEVED_DOCUMENTS>`.
- A-3: `documentFileName` is non-null for ingested chunks.
- A-4: Consumers do not depend on `UNKNOWN_ID` / `UNKNOWN_FILE` appearing.
- A-5: Within one answer, one documentId maps to one displayed filename (first-chunk rule).

## Dependencies
- **ragEval (`cpp-ui-e2e-serenity`)** — reason-column semantics drive FR-12; re-run after deployment.
- **`ai-document-shared-artefacts`** — shared displayed-filename helper (FR-8).
- **`ai-document-system-prompt-harness-eval`** — updated in the same PR (FR-14).
- **`hmcts/cpp-functionapp-deployment`** — promote the answer-retrieval release `version`; no settings change.

## Open questions — resolved 2026-10-20
1. **OQ-1** Entries with no `citationId` → counted in `invalid=N`.
2. **OQ-2** Compressed `pages` → **always recomputed** from the surviving individual pages; the model's `pageNumbers` string is ignored.
3. **OQ-3** Merge across a dropped marker → **merge** (FR-18 promoted to Must): invalid entries' markers are removed before run detection.
4. **OQ-4** Null-page documents in live data → low risk: current ingestion always sets an integer page (`DocumentChunkingService`).
5. **OQ-5** Null displayed filename → the document is uncitable (entry dropped).
6. **OQ-6** Harness persisted results → **persist the production `CitationOutcome` counts** (invalid entries, trimmed pages) in `RunResult`; offline tools read them.
7. **OQ-7** App Insights saved query/alert on the partial-invalidity WARN → post-release follow-up, outside this repo.
