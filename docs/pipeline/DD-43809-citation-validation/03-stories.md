# User Stories: Validate LLM citations against the retrieved chunks (DD-43809)

> Stage 03 artefact (story-writer). Sources: `00-input-brief.md`, `01-requirements.md` (approved, OQ-1…OQ-7 resolved), `02-design.md` (approved, no remaining questions).

## Jira mapping

All work is delivered under the single existing ticket **DD-43809**, in **one PR**. No sub-tasks or new tickets are created. The stories below are independently testable slices inside that ticket, ordered by dependency. Each slice is one commit, or a small group of commits, on `dev/feature-DD-43809-citation-validation`, and each leaves `mvn verify` green for the modules it touches. The story IDs are `DD-43809-S1` … `DD-43809-S8`.

Repo conventions:
- The PR description carries `**Jira:** DD-43809`, not the PR title.
- Commit messages and the PR body follow the attribution rules in the session reminder.
- Integration tests are optional. If they are run, notify Mahesh first so RBAC can be enabled.
- No ADR is needed (design, Follow-ups).

Fixtures used throughout (from the requirements):
- **Document A:** `id=aaaaaaaa-0000-4000-8000-000000000001`, shown filename `report-a.pdf`, retrieved pages {2, 3}.
- **Document B:** `id=bbbbbbbb-0000-4000-8000-000000000002`, shown filename `notes-b.pdf`, retrieved page {5}.

### Compile-continuity rule

S4 removes the chunk-less `processCitations(String)` and `processAndFormatCitations(String)`. Its commit must therefore also make the two production call sites compile:
- `ResponseGenerationService.toGuardedResponse` passes `CitationScope.from(chunkedEntries)`.
- The harness `CitationMetrics.compute` passes a scope built from chunks.

The behavioural work on those two sites (reason string, WARN, harness reporting) stays in S5 and S6.

## Summary table

| Story | Title | FR / NFR | ACs | Depends on | Size |
|---|---|---|---|---|---|
| DD-43809-S1 | Shared displayed-filename rule | FR-8, NFR-1, NFR-8 | AC-16 (part) | none | XS |
| DD-43809-S2 | `CitationScope` model and prompt-agreement test | FR-1, FR-8, FR-3 (matcher), E-1, E-2, E-9, E-17 | AC-16, AC-32, AC-33 (scope level) | S1 | S |
| DD-43809-S3 | `CitationEntryValidator` and `ValidationCounts` | FR-2…FR-5, FR-16, NFR-5, NFR-6 | AC-4…AC-14, AC-30…AC-33 | S2 | M |
| DD-43809-S4 | Processor pipeline: validate, remove markers, merge, render from retrieval | FR-5, FR-6, FR-7, FR-9, FR-10, FR-13, FR-15, FR-17, FR-18, NFR-6 | AC-2, AC-3, AC-6, AC-15, AC-17…AC-20, AC-25, AC-29 | S3 | L |
| DD-43809-S5 | Guard reason, partial-invalidity WARN and function-level behaviour | FR-1, FR-11, FR-12, FR-13, FR-17, NFR-2, NFR-3, NFR-4, NFR-7 | AC-1, AC-20…AC-28, AC-37 | S4 | M |
| DD-43809-S6 | Harness parity and persisted validation counts | FR-14, NFR-7, OQ-6 | AC-34, AC-35 | S4 (S5 not required) | M |
| DD-43809-S7 | Documentation | NFR-1, NFR-2, NFR-4 | AC-36 | S4, S5, S6 | S |
| DD-43809-S8 | Domain-neutral fixture clean-up (separate mechanical commit) | NFR-9 | none (non-behavioural) | S4, S5 (S6 for harness fixtures) | S |

Suggested commit order: S1, S2, S3, S4, S5, S6, S7, and S8 last so the behavioural diff is reviewable on its own. S5 and S6 are independent of each other once S4 is in.

---

## DD-43809-S1: Shared displayed-filename rule

### User story
As a **maintainer of the RAG service**,
I want **the rule that selects a document's displayed filename exposed as one public static method in shared artefacts**,
so that **the prompt builder and the citation validator can never disagree about which filename the model was shown**.

### Traceability
FR-8, NFR-1, NFR-8. Design Decision 1 ("Shared displayed-filename rule"). Implementation outline item 1.

### Background
`ChunkFormatterUtility.extractMaterialName` is private. The prompt builder uses `extractMaterialName(first).orElse(first.documentFileName())`. The method must be **static**, because `ResponseGenerationServiceTest` mocks the `ChunkFormatterUtility` instance and a static call is unaffected by that mock. Prompt bytes must stay identical, because the scoring function uses the same builder.

### Acceptance criteria
- [ ] AC-S1-1: Given a chunk whose `material_name` custom metadata is non-blank, when `displayedFileName(chunk)` is called, then it returns that `material_name` value.
- [ ] AC-S1-2: Given a chunk whose `material_name` is blank or absent, when `displayedFileName(chunk)` is called, then it returns `documentFileName`.
- [ ] AC-S1-3: Given a chunk with no `material_name` and a null `documentFileName`, when `displayedFileName(chunk)` is called, then it returns null and does not throw.
- [ ] AC-S1-4: Given any chunk list that existing `ChunkFormatterUtilityTest` cases cover, when `buildChunkContext` is called, then the output is byte-identical to before the change. The existing assertions are unchanged and pass.
- [ ] AC-S1-5: `buildChunkContext` calls `displayedFileName(first)`. `extractMaterialName` remains only as a private helper. Document grouping and order (`groupingBy`) are unchanged.

### NFR links
- NFR-1: no contract or prompt-format change.
- NFR-8: unit coverage for the new method.

### Files touched
- `ai-document-shared-artefacts/src/main/java/uk/gov/moj/cp/ai/util/ChunkFormatterUtility.java`: add `public static final String MATERIAL_NAME_METADATA_KEY = "material_name"` and `public static String displayedFileName(ChunkedEntry)`.
- `ai-document-shared-artefacts/src/test/java/.../ChunkFormatterUtilityTest.java`: additions only.

### Tests to add / migrate
- Add `displayedFileName` cases: `material_name` present, blank, absent, null `documentFileName`.
- Migrate: none. Existing assertions stay byte-identical.

### Out of scope
- Changing `buildChunkContext` grouping or document order.
- Any new env var.

### Size and dependencies
XS (under half a day). No dependencies.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs covered by automated unit tests.
- [ ] `mvn test -pl ai-document-shared-artefacts` and `mvn verify` green (Surefire and JaCoCo).
- [ ] No critical or high Snyk findings introduced.
- [ ] SonarQube quality gate passes on the Azure DevOps PR pipeline (new-code coverage and no new issues).
- [ ] Jira DD-43809 updated with test evidence (once, at PR level).

### Notes
The scoring function receives this change too. Its behaviour is byte-identical, so promoting it is optional.

---

## DD-43809-S2: `CitationScope` model and prompt-agreement test

### User story
As a **maintainer of the answer-retrieval function**,
I want **an immutable per-answer `CitationScope` (documentId to displayed filename plus retrieved page set) built once from the chunks sent to the model**,
so that **citation validation has a single, deterministic view of what the model was actually shown**.

### Traceability
FR-1 (scope source), FR-3 (filename matcher), FR-8, E-1, E-2, E-9, E-17, OQ-5. Design Decision 1. Outline item 2.

### Background
`CitationScope` (record, `uk.gov.moj.cp.retrieval.model`, in `ai-document-answer-retrieval-function`) holds `Map<String, CitableDocument>`. `CitableDocument(documentId, displayedFileName, SortedSet<Integer> pages)` has `matchesFileName(candidate)`. Construction rules from the design:
- Iterate in retrieval order.
- The first chunk per documentId sets the name, via `displayedFileName`.
- Use `containsKey` then `put`, **not** `putIfAbsent` / `computeIfAbsent`, so a null first-chunk name is not overridden by a later chunk. This keeps the scope in step with the prompt.
- Pages are the union of non-null `pageNumber`.
- A document with a null or blank name, or an empty page set, is omitted (uncitable).

### Acceptance criteria
- [ ] AC-S2-1: Given chunks for A (pages 2, 3) and B (page 5), when `CitationScope.from(chunks)` is built, then `document("aaaaaaaa-0000-4000-8000-000000000001")` has name `report-a.pdf` and pages {2,3}, and B has `notes-b.pdf` and {5}.
- [ ] AC-S2-2: Given a null or empty chunk list, when the scope is built, then it equals `CitationScope.empty()`.
- [ ] AC-S2-3 (AC-33, scope level): Given A has chunks with pages {2, null}, then A's pages are {2} and A is citable.
- [ ] AC-S2-4 (AC-32, scope level): Given B's only chunk has `pageNumber=null`, then B is omitted from the scope.
- [ ] AC-S2-5 (E-17): Given A's chunks carry differing `material_name` values, then the first chunk's displayed name is used.
- [ ] AC-S2-6 (OQ-5): Given the first chunk of a document has a null displayed name and a later chunk has a name, then the document is omitted (the later name does not win).
- [ ] AC-S2-7: `document(id)` is an exact, case-sensitive key lookup. A different case or surrounding whitespace returns empty; null returns empty.
- [ ] AC-S2-8: `matchesFileName("  REPORT-A.PDF ")` is true for `report-a.pdf`. `matchesFileName("notes-b.pdf")` is false. Null and blank are false.
- [ ] AC-S2-9: The record is immutable and has value equality. Two scopes built from equal chunk lists are `equals`.
- [ ] AC-16: Given `material_name` present, blank, absent, or differing across chunks, when `buildChunkContext(chunks)` is rendered and its `DOCUMENT_FILENAME` per documentId is parsed, then it equals `CitationScope.from(chunks)`'s displayed name for the same documentId.

### NFR links
- NFR-5: lookups built once per answer, linear in chunks.
- NFR-6: the scope is the sole source of "retrieved" truth.
- NFR-9: fixtures use `report-a.pdf`, `notes-b.pdf` and generated UUIDs.

### Files touched
- New `ai-document-answer-retrieval-function/src/main/java/uk/gov/moj/cp/retrieval/model/CitationScope.java`.
- New test-scope `.../src/test/java/uk/gov/moj/cp/retrieval/service/CitationScopeFixtures.java` with `doc(...)`, `chunksOf(...)` and `scopeOf(...)`.
- New tests `CitationScopeTest` and `CitationScopePromptAgreementTest`.

### Tests to add / migrate
Add the two new test classes and the fixtures helper. There is nothing to migrate.

### Out of scope
- The validator and processor (S3, S4).
- Any change to `buildChunkContext` grouping.

### Size and dependencies
S (about 1 day). Depends on S1.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs covered by automated unit tests (`CitationScopeTest`, `CitationScopePromptAgreementTest`).
- [ ] `mvn verify` green for `ai-document-answer-retrieval-function`.
- [ ] No critical or high Snyk findings introduced.
- [ ] SonarQube quality gate passes on the Azure DevOps PR pipeline.
- [ ] Jira DD-43809 updated with test evidence.

---

## DD-43809-S3: `CitationEntryValidator` and `ValidationCounts`

### User story
As a **maintainer of the answer-retrieval function**,
I want **a stateless, package-private validator that checks each `FACT_MAP_JSON` entry against the `CitationScope` and reports valid, dropped and trimmed outcomes with counts**,
so that **every citation rule is unit-testable on its own and `CitationProcessor` stays within Sonar's complexity limits**.

### Traceability
FR-2, FR-3, FR-4, FR-5 (drop), FR-16, E-1…E-9, E-12, NFR-5, NFR-6, OQ-1. Design Decision 3 step 5 and Decision 4 (`ValidationCounts`). Outline item 3.

### Background
`CitationEntryValidator.validate(List<Map<String,Object>> entries, CitationScope scope)` returns `ValidationResult(resolvedById, droppedIds, ValidationCounts)`. `ResolvedCitation(citationId, CitableDocument, SortedSet<Integer> pages, int trimmedPages)`. `ValidationCounts` is nested in `CitationProcessor` (so S3 introduces the nested type, and S4 wires it into `CitationOutcome`). Per-entry check order, with one cause per entry:
1. `citationId` present: the key is `stringifyJsonValue` then trimmed, so `1` and `"1"` are the same key. Missing or blank means cause `noCitationId`.
2. `documentId` is a JSON string and an exact key in the scope. Otherwise cause `documentId`.
3. `documentFilename` matches that document's displayed name. Otherwise cause `filename`.
4. `individualPageNumbers` is a JSON array or comma string. Split, trim, drop empties and parse with `Integer.parseInt`. A token survives only if it is a retrieved page of that document. Others are trimmed. Duplicates are de-duplicated and not counted. No surviving page means cause `pages`.

First valid entry per citationId wins (`putIfAbsent` for valid entries only). Invalid duplicates are counted. Valid shadowed duplicates are not counted. The model's `pageNumbers`, `documentId` and `documentFilename` values are never carried forward.

### Acceptance criteria
- [ ] AC-4: Given `{citationId:1, documentId:"aaaaaaaa-0000-4000-8000-0000000000", documentFilename:"report-a.pdf", individualPageNumbers:"2"}`, then the entry is dropped and `invalidEntries` = 1.
- [ ] AC-5: Given an entry whose documentId differs from A's only by case or surrounding whitespace, then it is dropped (exact match, no trim).
- [ ] AC-6 (validator level): Given an entry with no `documentId`, or a non-string or null one, then it is dropped without an exception.
- [ ] AC-7: Given an entry for A with `documentFilename:"  REPORT-A.PDF "`, then it is kept and the resolved document's displayed name is `report-a.pdf`.
- [ ] AC-8: Given an entry for A with `documentFilename:"notes-b.pdf"`, then it is dropped (E-1, E-3).
- [ ] AC-9: Given A's chunks carry `material_name=summary-a` and `documentFileName=report-a.pdf`, then `"report-a.pdf"` is dropped and `"summary-a"` is kept.
- [ ] AC-10 (E-2): Given A and B are both shown as `report-a.pdf`, then `{documentId:A, documentFilename:"report-a.pdf", individualPageNumbers:"2,5"}` is kept with pages {2}, the resolved document is A, and `trimmedPages` = 1.
- [ ] AC-11: Given `individualPageNumbers:"2, 7, iv"` for A, then the entry is kept with pages {2} and `trimmedPages` = 2.
- [ ] AC-12 (E-6): `[2,3]` (JSON array) and `"2,3"` (string) produce identical resolved pages {2,3}.
- [ ] AC-13: Given `"7"`, `"N/A"`, `""` or a missing field for A, then the entry is dropped (cause `pages`).
- [ ] AC-14 (E-5): Given `"2-3"`, then the token is trimmed and the entry is dropped.
- [ ] AC-30 (E-7, E-8): Given `{citationId:1, documentId:<garbled>}` then `{citationId:"1", documentId:A, ...valid}`, then the second entry resolves id `1`, `invalidEntries` = 1, and `1` is not in `droppedIds`.
- [ ] AC-31: Given two valid entries with `citationId` 1 (A page 2, then B page 5), then id `1` resolves to A, and the shadowed B entry is not counted as invalid.
- [ ] AC-32: Given B's only chunk has a null page, then an entry for B with `"5"` or `"N/A"` is dropped (B is not in the scope).
- [ ] AC-33: Given A has pages {2, null}, then an entry citing `"2"` is kept.
- [ ] AC-S3-1 (E-12, OQ-1): Given an entry with no `citationId`, then it is dropped and counted in `invalidEntries` (cause `noCitationId`).
- [ ] AC-S3-2 (E-4): The page token `iv` and the decimal `7.0` are trimmed and never throw.
- [ ] AC-S3-3 (invariant): `invalidEntries == noCitationId + invalidDocumentId + invalidFileName + noValidPage`, one cause per entry (first failing check).
- [ ] AC-S3-4 (E-13): An invalid entry referenced by no marker is still counted.
- [ ] AC-S3-5 (FR-15, validator level): Given the empty scope, then every entry is dropped.

### NFR links
- NFR-5: linear in entries plus chunks, no per-entry rescans of the chunk list.
- NFR-6: nothing returned from the validator carries a documentId or page absent from the scope.
- NFR-9: fixtures A and B and generated UUIDs only.

### Files touched
- New `.../retrieval/service/CitationEntryValidator.java` (package-private).
- `.../retrieval/service/CitationProcessor.java`: only the new nested `ValidationCounts` record is added in this story (full wiring is in S4).
- New test `CitationEntryValidatorTest`. The processor-level `CitationProcessorValidationTest` is built up in S4.

### Tests to add / migrate
Add `CitationEntryValidatorTest` covering each AC above. Migrate nothing.

### Out of scope
- Rendering, merging and marker removal (S4).
- Guard reason and WARN (S5).

### Size and dependencies
M (1 to 2 days). Depends on S2.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs covered by automated unit tests, with branch coverage on the validator.
- [ ] `mvn verify` green for `ai-document-answer-retrieval-function`.
- [ ] No critical or high Snyk findings introduced.
- [ ] SonarQube quality gate passes on the Azure DevOps PR pipeline (cognitive complexity within limits, new regexes bounded).
- [ ] Jira DD-43809 updated with test evidence.

---

## DD-43809-S4: Processor pipeline — validate, remove unresolvable markers, merge, render from retrieval

### User story
As an **API consumer**,
I want **every rendered citation to carry a documentId, filename and pages taken from the retrieved chunks, with invalid citations removed**,
so that **I never receive a citation link that points at no document**.

### Traceability
FR-5, FR-6, FR-7, FR-9, FR-10, FR-13, FR-15, FR-17, FR-18, E-10, E-11, E-13, E-14, E-15, NFR-1, NFR-6. Design Decision 2 and Decision 3 steps 0 to 10. Outline items 4 and 7 (processor suites).

### Background
The public API changes to `processCitations(String raw, CitationScope scope)` and `processAndFormatCitations(String raw, CitationScope scope)`. The single-argument overloads are removed so that AC-3 is enforced at compile time. A null scope is treated as `CitationScope.empty()` (every entry is invalid, never "skip validation").

Pipeline order: parse, then `emptyFactMap = originalList.isEmpty()`, then validate, then remove markers with no valid entry (6a bare markers, with run-gap closing; 6b drift forms of dropped ids), then merge runs by retrieval documentId with the union of surviving pages, then substitute from the scope, then the safety strip, then count.

**Confirmed orphan-marker rule (PO, 2026-10-20):** "ids with no valid entry" covers ids with **no entry at all** as well as ids whose entries were all invalid. Both kinds of bare marker are removed **before** run detection. So `[1][9][2]` (no entry for 9; 1 and 2 valid, same document) merges into **one** citation. The pinned test `CitationProcessorMergeTest.unknownIdInsideRun_BreaksMergeAndIsStripped` is migrated to `unknownIdInsideRun_IsStrippedAndRunStillMerges`, expecting one `Pages 1,2|1,2` citation. Removing a marker inside a run also removes its separating whitespace (up to 3 characters), so `[1] [2] [3]` with `[2]` dropped becomes `[1] [3]` and still merges. Outside a run, only the marker is removed (`Text [2].` becomes `Text .`, as today).

`UNKNOWN_ID`, `UNKNOWN_FILE` and `resolveCompressedPages` are deleted. Compressed pages are always recomputed from surviving pages (OQ-2). `CitationOutcome` gains `ValidationCounts validation` plus `invalidEntries()` and `trimmedPages()`. The merge INFO line drops the documentId. The processor emits no WARN for validation drops.

### Acceptance criteria
- [ ] AC-2 / AC-S4-1 (FR-15): Given a null or empty scope passed directly to the processor, when a raw answer with valid-looking entries is processed, then every entry is invalid, `renderedCitations` = 0 and no `::(Source:` appears.
- [ ] AC-3: No call to `CitationProcessor` without a chunk scope remains in production code or tests (compile-level; the single-argument overloads no longer exist).
- [ ] AC-4 (end to end): Given the garbled-id entry and text `Fact one [1].`, then the output has no `[1]`, no `::(Source:`, and `invalidEntries()` = 1.
- [ ] AC-6: Given an entry with no `documentId`, then the output contains neither `UNKNOWN_ID` nor `UNKNOWN_FILE`, and no citation renders.
- [ ] AC-15 (FR-7, E-15): Given a valid entry for A with model `pageNumbers:"2-7"` and `individualPageNumbers:"2,3,7"`, then the output renders exactly `::(Source: [report-a.pdf], Pages 2,3|2,3|documentId=aaaaaaaa-0000-4000-8000-000000000001)`.
- [ ] AC-17: Given `Claim [1][2].` with both entries for A (pages 2 and 3) and entry 2's documentId garbled, then one citation for A with pages `2` renders and `[2]` is absent.
- [ ] AC-18 (FR-18, E-11): Given `Claim [1][2][3].` with 1 (page 2) and 3 (page 3) valid for A and 2 invalid, then a single merged citation for A with pages `2,3` renders and no bare marker remains.
- [ ] AC-19: Given `Claim [1][2].` with 1 valid for A and 2 valid for B, then two separate citations render.
- [ ] AC-20 (FR-10, E-14): Given two entries, both invalid, and two markers, then `emptyFactMap` = false, `renderedCitations` = 0 and `invalidEntries()` = 2. The same holds for all entries invalid with no markers.
- [ ] AC-25 (processor level): Given a degraded answer with nothing dropped (no JSON block, or unparseable JSON), then `ValidationCounts.NONE` applies and `invalidEntries()` = 0.
- [ ] AC-S4-2 (orphan rule): Given `[1][9][2]` with entries only for ids 1 and 2 (valid, same document A), then one merged citation renders and `[9]` is removed. The orphan WARN (existing wording) still fires for 9.
- [ ] AC-S4-3 (gap closing): Given `[1] [2] [3]` with 2 dropped, then 1 and 3 merge into one citation.
- [ ] AC-S4-4 (drift forms, 6b): Given a dropped id 2 and the text `Claim [Source 2].`, then the bracketed form is removed (not left as literal brackets) and counted in `strippedMarkers`.
- [ ] AC-S4-5 (FR-10): A genuine `[]` with no markers is still reported as `emptyFactMap` = true with `renderedCitations` = 0 (accepted refusal, unchanged).
- [ ] AC-S4-6 (FR-16, end to end): Given `X [1]. Y [1].` with the AC-30 entries, then both markers render A.
- [ ] AC-29: The merge INFO line contains no documentId (log-capture assertion, or a code-level check that the argument is removed).
- [ ] AC-S4-7 (counts invariants): `stripped == 0 && invalid == 0` implies every bare marker rendered. `jsonBlock && !emptyFactMap && rendered == 0` implies degraded. `strippedMarkers` is numerically identical to today whenever `invalidEntries == 0`.
- [ ] AC-S4-8 (existing behaviour preserved): the no-block and parse-failure paths still strip all bare markers. The existing joined-id normalisation and `compressPageRange` behave as before.

### NFR links
- NFR-1: `CITATION_FORMAT` unchanged. Pages render sorted, de-duplicated and comma-joined (merged citations already used this form).
- NFR-5: lookups built once per answer.
- NFR-6: zero unvalidated citations rendered.
- NFR-9: neutral fixtures.

### Files touched
- `ai-document-answer-retrieval-function/src/main/java/uk/gov/moj/cp/retrieval/service/CitationProcessor.java`: new API, pipeline, `CitationOutcome` and `ValidationCounts`, deletions, Javadoc ("Validation" bullet, merge groups by the validated retrieval documentId, markers of ids with no valid entry are removed before run detection).
- Minimal compile-keeping edits (behavioural work deferred to S5 and S6):
  - `ResponseGenerationService.toGuardedResponse` passes `CitationScope.from(chunkedEntries)`.
  - Harness `CitationMetrics.compute(...)` and its caller pass a scope built from the run's chunks.
- Test migrations (see below) plus the new `CitationProcessorValidationTest`.

### Tests to add / migrate
- **Add `CitationProcessorValidationTest`**, one test per AC above using fixtures A and B. Add E-13, E-14, the spaced-gap merge, the drift-form removal and the counts invariants.
- **Migrate:**
  - `CitationProcessorTest` (5 sites): `SCOPE = scopeOf(doc("doc123","file.pdf",10,11,12,13))`. Add the null-scope test (AC-2).
  - `CitationProcessorMergeTest` (13): per-test scopes where an id is reused with different filenames.
    - `nonNumericPageTokens_AreToleratedInUnion` becomes `…AreTrimmedFromUnion`, expecting `Pages 2,3|2,3`.
    - `unknownIdInsideRun_BreaksMergeAndIsStripped` becomes `unknownIdInsideRun_IsStrippedAndRunStillMerges`, expecting one `Pages 1,2|1,2` citation.
  - `CitationProcessorGpt41Test` (21): `SCOPE = scopeOf(doc("abc-123","report-a.pdf",7,8,9), doc("a-id","a.pdf",1), doc("b-id","b.pdf",2))`. `c3_missingDocumentId` now asserts no `::(Source:`, no `UNKNOWN_ID` / `UNKNOWN_FILE`, and no `[1]`.
  - `CitationProcessorGpt51Test` (5): `SCOPE = scopeOf(doc("abc-123","report-a.pdf",7))`.
  - `CitationProcessorDriftTest` (4): same scope (Drift 4 takes the no-block path).
  - `CitationOutcomeTest` (8 and 1 delegate): `SCOPE = scopeOf(doc("docA","report-a.pdf",3,7))`. Add `invalidEntries()==0` and `trimmedPages()==0` to the clean case.

### Out of scope
- Guard reason string, partial-invalidity WARN, `ResponseGenerationServiceTest` behavioural changes (S5).
- Harness reporting and persistence (S6).
- Repairing garbled ids. Changing the shape of `isAcceptable`.

### Size and dependencies
L (2 to 3 days). It is the largest slice because of the roughly 60-call-site test migration. The migration is mechanical (`(raw)` becomes `(raw, SCOPE)`) except for the three assertion changes listed above. Depends on S3.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs covered by automated unit tests, with every migrated suite green.
- [ ] `mvn verify` green for `ai-document-answer-retrieval-function` and `ai-document-system-prompt-harness-eval` (compile continuity).
- [ ] No critical or high Snyk findings introduced.
- [ ] SonarQube quality gate passes on the Azure DevOps PR pipeline (no new complexity or ReDoS hotspots; new regexes use bounded quantifiers).
- [ ] Jira DD-43809 updated with test evidence.

### Notes
- The pinned merge-test change is the single behavioural change of existing tests and must be called out in the PR description.
- Output normalisation is a visible value change inside the existing free-text string: individual pages sorted, de-duplicated and unspaced, and filename casing taken from retrieval (R-5). No contract impact.

---

## DD-43809-S5: Guard reason, partial-invalidity WARN and function-level behaviour

### User story
As an **operator and on-call engineer**,
I want **total citation invalidity to flow through the existing citation guard with an `invalid=N` count, and partial invalidity to be accepted with a single counts-only WARN and no reason**,
so that **real degradation stays visible in the reason column that ragEval gates on, while partial invalidity is observable in logs without failing the quality suite**.

### Traceability
FR-1 (processor receives the retrieved chunks), FR-11, FR-12, FR-13, FR-17, NFR-2, NFR-3, NFR-4, NFR-7, E-16. Design Decision 4 and the Reliability section. Outline items 5 and 6.

### Background
`ResponseGenerationService.generateResponse` builds `CitationScope.from(chunkedEntries)` **once**, after the empty-chunks short-circuit, from the same list passed to `buildChunkContext`. `toGuardedResponse(raw, scope)` appends `, invalid=%d` to the reason: `[guard=%s] Citations missing: jsonBlock=%s, inlineMarkers=%d, rendered=%d, stripped=%d, invalid=%d`. `isAcceptable` is **not** modified. When the answer is accepted with `renderedCitations >= 1` and (`invalidEntries > 0` or `trimmedPages > 0`), exactly one WARN is logged with counts only, and `LlmResponse.reason` stays null. The existing accepted INFO line appends `invalid` and `trimmedPages`. `AnswerGenerationFunction` and `SyncAnswerGenerationFunction` are unchanged (the reason is an opaque string to them). `CitationDegradedException` gets a Javadoc-only update.

### Acceptance criteria
- [ ] AC-1: Given `generateResponse` is called with chunks for A and B, when the answer is post-processed, then `CitationProcessor` receives `CitationScope.from(thatSameChunkList)` (verified with `eq`).
- [ ] AC-20 (guard level): Given two entries, both invalid, and two markers, then the answer is treated as degraded (`isAcceptable` fails), not as a refusal.
- [ ] AC-21: Given async mode and `CITATION_GUARD_MODE=deliver`, when every entry is invalid, then `CitationDegradedException` is thrown with a message containing `invalid=2` and the prefix `[guard=deliver] Citations missing:`. On exhaustion the persisted `ANSWER_GENERATED` row carries a reason starting `[guard=deliver] Citations missing:` (existing function-level behaviour, covered by `AnswerGenerationFunctionTest`).
- [ ] AC-22: Given sync mode and `reject`, when every entry is invalid, then the existing reject behaviour applies (200 with the failure sentinel, scoring skipped).
- [ ] AC-23: Given `off`, when every entry is invalid, then the result is `ANSWER_GENERATED` with reason `[guard=off] Citations missing: …, invalid=2`.
- [ ] AC-24: Given a `[]` FACT_MAP_JSON with no markers, then the answer is an accepted refusal with no reason (unchanged).
- [ ] AC-25: Given a degraded answer with nothing dropped (for example no JSON block), then the reason ends `invalid=0`.
- [ ] AC-26: Given three entries, one invalid, two rendering, then the result is `ANSWER_GENERATED`, `reason` is null, no exception is thrown, and exactly one WARN is logged across both loggers.
- [ ] AC-27: That WARN contains the counts and none of the fixture documentIds, filenames, query text or answer text. It also contains no literal `documentId=` token (cause labels are `causeCitationId`, `causeDocument`, `causeFilename`, `causePages`).
- [ ] AC-28: Given only pages trimmed (no entry dropped), then the answer is accepted with no reason and the WARN reports `trimmedPages` greater than 0.
- [ ] AC-37 (NFR-3): With a mocked `ChatService`, `callModel` is invoked exactly once, and identical inputs give identical output.
- [ ] AC-S5-1 (E-16): Given two attempts with different chunk lists, then each attempt validates against its own chunks (nothing is cached across calls).
- [ ] AC-S5-2 (NFR-2): No new environment variable is read. No `local.settings.sample.json` or `host.json` changes.

### NFR links
- NFR-2: zero new env vars, `CITATION_GUARD_MODE` semantics unchanged.
- NFR-3: no extra LLM, embedding, search or storage calls.
- NFR-4: log hygiene per `docs/transaction-scoped-logging.md` (SLF4J, MDC, no IDs or text in messages).
- NFR-7: total invalidity is measurable through `invalid=N`, partial invalidity through the WARN.

### Files touched
- `.../retrieval/service/ResponseGenerationService.java`: scope built once, `toGuardedResponse(raw, scope)`, reason format, accepted INFO, single WARN, Javadoc.
- `.../retrieval/exception/CitationDegradedException.java`: Javadoc only.
- Test support: new test-scope `LogCapture` (`AutoCloseable`, log4j2 `AbstractAppender` on named loggers). If the build complains, declare `log4j-core` test-scope in the retrieval pom.

### Tests to add / migrate
- **Add `ResponseGenerationServiceCitationValidationTest`:** real `CitationProcessor` and `ChunkFormatterUtility`, mocked `ChatService` and `UserInstructionService`. Covers AC-1 end to end, AC-20, AC-21 (exception message), AC-23, AC-24, AC-25, AC-26 (exactly one WARN), AC-27, AC-28, AC-29 (merge INFO has no documentId), AC-37.
- **Migrate `ResponseGenerationServiceTest`:**
  - Stubs become `processCitations(eq(raw), any(CitationScope.class))`.
  - The first test verifies `processCitations(raw, CitationScope.from(chunkedEntries))`.
  - `never().processCitations(any(), any())`.
  - Outcome constructors get `ValidationCounts.NONE`.
  - Two reason assertions gain `, invalid=0`.
  - Add a degraded outcome with `ValidationCounts(2,0,2,0,0,0)` where the reason ends `invalid=2` (deliver throws, off returns).
  - Add an accepted outcome with invalid > 0 where the reason is null.
- **Function-level tests:** `AnswerGenerationFunctionTest` and `SyncAnswerGenerationFunctionTest` need no change (they are message-agnostic). Optionally refresh their literal reason strings to show `, invalid=0`.

### Out of scope
- Any change to retry, lease, exhaustion or `maxDequeueCount` mechanics.
- The `isAcceptable` empty-`[]` loophole (DD-43838).
- A reason value for partial invalidity (deliberately never set, FR-12).
- An App Insights saved query or alert (OQ-7, post-release, outside the repo).

### Size and dependencies
M (1 to 2 days). Depends on S4. Independent of S6.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs covered by automated tests (unit and the service-level integration-style test above).
- [ ] `mvn verify` green for `ai-document-answer-retrieval-function`, including the unchanged `AnswerGenerationFunctionTest` and `SyncAnswerGenerationFunctionTest`.
- [ ] No critical or high Snyk findings introduced.
- [ ] SonarQube quality gate passes on the Azure DevOps PR pipeline.
- [ ] Jira DD-43809 updated with test evidence.
- [ ] If the optional integration suite is run, Mahesh was notified beforehand so RBAC could be enabled.

### Notes
The expected operational effect is a higher degraded and redelivery rate for answers that previously passed with broken links (R-1). Say this in the PR description. Run the harness (S6) on a retrieval snapshot to baseline `invalid` and `trimmed` rates before release.

---

## DD-43809-S6: Harness parity and persisted validation counts

### User story
As an **evaluation-harness operator**,
I want **the offline harness to evaluate citations against the same chunks used for generation, and to report and persist the production invalid and trimmed counts**,
so that **I can measure invalid-citation rates per prompt and model before and after release, with results that match production**.

### Traceability
FR-14, NFR-7, E-18, OQ-6, R-4. Design Decision 2 ("Harness: `RunResult` and persisted counts"). Outline items 8 to 11.

### Background
`CitationMetrics.compute(LlmResponse, List<ChunkedEntry>)` builds `CitationScope.from(chunks)` and reruns `CitationProcessor`. `Compliance` gains `invalidCitations` and `trimmedPages`, read from the outcome (so `renderedCitations` and `strippedMarkers` now match production). `TestHarness.runCell` computes `Compliance` once with the run's chunks and stores it on `RunResult` (new trailing `Compliance compliance` field, null when `response` is null). The identity-map memo `COMPLIANCE_BY_RESPONSE` and `computeCompliance` are removed. `RunResultStore.metricsOf(r.compliance())` adds `invalidCitations` and `trimmedPages`, and `meta.citationValidation = true`.

Backward compatibility: persisted run JSON is only read as `JsonNode` (`BaselineJudgeTool.rowsOf` never reads `metrics`). Any current or future reader treats absent `metrics.invalidCitations` / `trimmedPages` or absent `meta.citationValidation` as **"not measured"** (`n/a`), never 0.

### Acceptance criteria
- [ ] AC-34: Given a harness run with one invalid and one valid entry, then `CitationMetrics.Compliance` reports `invalidCitations` = 1 and `renderedCitations` = 1, and the summary and detail output show both values.
- [ ] AC-35: Given a run with only valid citations, then `invalidCitations` = 0 and `renderedCitations` matches the value production computes for the same raw text and chunks.
- [ ] AC-S6-1 (E-18): `CitationMetrics.compute` uses the run's chunks. It has no overload that skips validation.
- [ ] AC-S6-2: `printSummary` shows `invalid` (and `rendered`) columns. The per-cell consistency stats show `invalid` as the number of OK runs with at least one invalid entry, as x/n. `printRunDetail` shows `invalid: {} | trimmedPages: {}`. The legend lines are updated.
- [ ] AC-S6-3: `RunResultStore` persists `metrics.invalidCitations`, `metrics.trimmedPages` and `meta.citationValidation=true`.
- [ ] AC-S6-4: Old stored result files (without the new fields) still load in `BaselineJudgeTool`, and any display of those fields prints `n/a`, not 0.
- [ ] AC-S6-5: SKIPPED and ERROR runs (null response) carry a null `compliance` and do not throw in any printer or in persistence.

### NFR links
- NFR-7: the partial-invalidity signal is available offline through the harness metric.
- NFR-9: neutral fixtures.

### Files touched
- `ai-document-system-prompt-harness-eval/src/main/java/uk/gov/moj/cp/harness/CitationMetrics.java`.
- `.../harness/TestHarness.java`: `RunResult`, `runCell`, `printSummary`, `computeCellStats` / `printConsistency`, `printRunDetail`.
- `.../harness/RunResultStore.java`: `metricsOf`, `meta`, class Javadoc with the missing-field rule.
- `ai-document-system-prompt-harness-eval/README.md`: metrics table (documentation lives in S7).

### Tests to add / migrate
- **Migrate `CitationMetricsTest`:** the `compute(raw, formatted)` helper gains chunks (`scopeChunks = chunksOf(doc("doc-A","f.pdf",1,2,3,4,5), doc("doc-B","f.pdf",2))`). Add AC-34 and AC-35.
- **Migrate `ResponseQualityComparatorTest`:** add a trailing `null` to the 5 `new RunResult(...)` calls. Do not add a secondary constructor, because it would hide the new field.
- **Add:** a `RunResultStore` test for the new metrics and meta keys and for null-compliance rows (AC-S6-3, AC-S6-5).

### Out of scope
- Persisting chunks in `RunResult` (R-4 and OQ-6 settle on persisting counts only).
- Exposing the production `CitationOutcome` through `LlmResponse`.
- Changing `BaselineJudgeTool` behaviour.

### Size and dependencies
M (1 to 2 days). Depends on S4 (the `CitationOutcome` counts and the scope API). Independent of S5.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs covered by automated unit tests.
- [ ] `mvn verify` green for `ai-document-system-prompt-harness-eval` (and the full reactor).
- [ ] No critical or high Snyk findings introduced.
- [ ] SonarQube quality gate passes on the Azure DevOps PR pipeline.
- [ ] Jira DD-43809 updated with test evidence.

### Notes
Harness baselines across the change boundary are not strictly comparable: the old side may contain unvalidated citations. Flag this in the README (S7).

---

## DD-43809-S7: Documentation

### User story
As a **maintainer and on-call engineer**,
I want **CLAUDE.md, the logging reference, the harness README and the Javadoc to describe citation validation, the `invalid=N` reason field and the partial-invalidity behaviour**,
so that **the behaviour, the reason-column semantics and the (unchanged) configuration surface are discoverable without reading the code**.

### Traceability
NFR-1, NFR-2, NFR-4, AC-36. Design "Documentation updates (exact)". Outline item 12.

### Acceptance criteria
- [ ] AC-S7-1: Root `CLAUDE.md`, citation-guard bullet:
  - The degraded definition now includes "or every FACT_MAP_JSON entry failed validation against the retrieved chunks, so no citation rendered".
  - It adds one sentence on validation rules: documentId exact; filename trimmed and case-insensitive against the displayed name (`material_name` else `documentFileName` of the document's first retrieved chunk); pages must be retrieved pages of that document and invalid pages are trimmed; invalid entries dropped with no repair; kept citations render documentId, filename and pages from retrieval.
  - The reason example is updated to `[guard=off] Citations missing: …, invalid=N`, defined as FACT_MAP_JSON entries dropped by validation.
  - It states that **partial invalidity** is accepted as `ANSWER_GENERATED` with **no** reason (the reason column stays reserved for real degradation, which ragEval gates on) and logs a single counts-only WARN.
  - It states explicitly that there is no new env var and no change to retry, lease or exhaustion mechanics.
- [ ] AC-S7-2: Root `CLAUDE.md`, "Query & Answer Generation Pipeline" gains one line saying citation post-processing validates against the retrieved chunks, with a link to `docs/pipeline/DD-43809-citation-validation/02-design.md`.
- [ ] AC-S7-3: `docs/transaction-scoped-logging.md` no longer lists `CitationProcessor`'s merge line among the statements that keep an ID (about line 190). The sample reason (about line 150) optionally appends `, invalid=0`.
- [ ] AC-S7-4: Javadoc updated on `CitationProcessor` (Validation bullet; merge groups by retrieval documentId; markers of ids with no valid entry removed before run detection), `CitationDegradedException`, `ResponseGenerationService.generateResponse` and `toGuardedResponse`, and `CitationMetrics` ("pure function of the response **and its chunks**").
- [ ] AC-S7-5: `ai-document-system-prompt-harness-eval/README.md` metrics table adds `invalid` and `trimmed` rows. `rendered` and `stripped` text says "stripped includes markers of citations dropped by validation". It notes that pre-DD-43809 result files carry no validation counts (`n/a`) and that baselines across the change are not strictly comparable.
- [ ] AC-36: No change to the `api-cp-ai-rag` spec, the generated models, any env-var key, any `local.settings.sample.json` or any `host.json` (verified by diff review).

### NFR links
- NFR-1, NFR-2: documentation states "no contract change, no new env var".
- NFR-4: logging reference remains accurate.

### Files touched
- `/Users/moj/projects/code/cp-ai-rag-service/CLAUDE.md`
- `/Users/moj/projects/code/cp-ai-rag-service/docs/transaction-scoped-logging.md`
- `/Users/moj/projects/code/cp-ai-rag-service/ai-document-system-prompt-harness-eval/README.md`
- Javadoc in the classes listed above (these edits may be committed with the owning code story).

### Tests to add / migrate
None (documentation). Review only. `mvn verify` must remain green.

### Out of scope
- Any spec PR to `api-cp-ai-rag`. Running `api-contract-check` is optional (no endpoint touched).
- Updating any other repo (for example the `cpp-functionapp-deployment` release bump, which is a manual post-merge deployment step).

### Size and dependencies
S (half a day). Depends on S4, S5 and S6, because the text must match the final behaviour.

### Definition of done
- [ ] Code (docs) reviewed and approved.
- [ ] Domain-agnostic wording confirmed in every new sentence.
- [ ] `mvn verify` green (no code change expected).
- [ ] SonarQube quality gate passes on the Azure DevOps PR pipeline.
- [ ] Jira DD-43809 updated.

---

## DD-43809-S8: Domain-neutral fixture clean-up (separate mechanical commit)

### User story
As a **maintainer**,
I want **domain-specific wording and fixture names removed from the citation test suites and harness fixtures we are already editing**,
so that **the codebase uses only domain-agnostic fixtures (`report-a.pdf`, `notes-b.pdf`, generated UUIDs) and the behavioural diff stays reviewable on its own**.

### Traceability
NFR-9; project rule "domain-agnostic wording". Design "Test strategy" ("Domain-neutral wording").

### Background
The touched suites contain domain-specific fixture names and prose. Each touched line is being edited anyway for scope migration. This story is a **purely mechanical, non-behavioural commit**, made last in the PR, so reviewers can read S1 to S7 as behavioural changes and S8 as renaming only. It must not change any assertion's meaning.

### Acceptance criteria
- [ ] AC-S8-1: Given the test files touched in S4 to S6, when they are searched for domain-specific fixture names and prose (found in a review pass over those files), then none remain. Neutral replacements are `report-a.pdf`, `notes-b.pdf`, "The item was recorded" and similar.
- [ ] AC-S8-2: Given the commit, when the full test suite runs, then the pass and fail result set is identical to the pre-commit state. No assertion is added, removed or weakened.
- [ ] AC-S8-3: Renamed fixture filenames stay consistent with their scopes (a filename change to a fixture is mirrored in its `doc(...)` scope definition in the same suite).
- [ ] AC-S8-4: The commit contains test and fixture files only, and no production code.
- [ ] AC-S8-5: No domain terms are introduced by any new text in S1 to S7 (new tests, log messages, Javadoc, docs, PR text).

### NFR links
- NFR-9: domain neutrality.

### Files touched
Test and fixture files only, limited to those already touched in the PR:
- `CitationProcessorTest`, `CitationProcessorMergeTest`, `CitationProcessorGpt41Test`, `CitationProcessorGpt51Test`, `CitationProcessorDriftTest`, `CitationOutcomeTest`, `ResponseGenerationServiceTest`
- `CitationMetricsTest`, `ResponseQualityComparatorTest`
- `ChunkFormatterUtilityTest` additions, if any term appears there

### Tests to add / migrate
None added. Existing tests are renamed in prose and fixtures only.

### Out of scope
- Sweeping domain terms in files the PR does not otherwise touch.
- Any production code or behaviour change.

### Size and dependencies
S (half a day). Depends on S4 and S5 (and S6 for the harness fixtures), because it edits their test files. Commit it last.

### Definition of done
- [ ] Code reviewed and approved, as a diff reviewable separately from the behavioural commits.
- [ ] `mvn verify` green, with the same test results as the preceding commit.
- [ ] No critical or high Snyk findings introduced.
- [ ] SonarQube quality gate passes on the Azure DevOps PR pipeline.
- [ ] Jira DD-43809 updated.

---

## Requirement traceability matrix

| Requirement | Stories |
|---|---|
| FR-1 | S2 (scope source), S5 (AC-1) |
| FR-2 | S3 |
| FR-3 | S2 (matcher), S3 |
| FR-4 | S3 |
| FR-5 | S3 (drop), S4 (marker removal) |
| FR-6 | S4 |
| FR-7 | S4 |
| FR-8 | S1, S2 |
| FR-9 | S4 |
| FR-10 | S4, S5 |
| FR-11 | S5 |
| FR-12 | S5 |
| FR-13 | S4, S5 |
| FR-14 | S6 |
| FR-15 | S3 (validator), S4 |
| FR-16 | S3, S4 |
| FR-17 | S4 (merge INFO), S5 (WARN) |
| FR-18 | S4 |
| NFR-1 | S1, S4, S7 |
| NFR-2 | S5, S7 |
| NFR-3 | S5 |
| NFR-4 | S5, S7 |
| NFR-5 | S2, S3, S4 |
| NFR-6 | S3, S4 |
| NFR-7 | S5, S6 |
| NFR-8 | S1 to S6 (tests in each) |
| NFR-9 | S8 (plus neutral fixtures in every story) |

| AC | Story | AC | Story |
|---|---|---|---|
| AC-1 | S5 | AC-20 | S4, S5 |
| AC-2 | S4 | AC-21 to AC-23 | S5 |
| AC-3 | S4 | AC-24 | S4, S5 |
| AC-4 | S3, S4 | AC-25 | S4, S5 |
| AC-5 | S3 | AC-26 to AC-28 | S5 |
| AC-6 | S3, S4 | AC-29 | S4 (INFO line), S5 (log-capture) |
| AC-7 to AC-14 | S3 | AC-30, AC-31 | S3, S4 |
| AC-15 | S4 | AC-32, AC-33 | S2, S3 |
| AC-16 | S1, S2 | AC-34, AC-35 | S6 |
| AC-17 to AC-19 | S4 | AC-36 | S7 |
| | | AC-37 | S5 |

Edge cases E-1 to E-18 are covered by the stories above:
- E-1 to E-9, E-12: S2, S3.
- E-10, E-11, E-13 to E-15: S4.
- E-16: S5.
- E-17: S2.
- E-18: S6.

## Release notes for the PR

- Single PR under DD-43809. Every merge to `main` cuts a release.
- After merge, promote the answer-retrieval function `version` in `hmcts/cpp-functionapp-deployment` (`vars/<env>/ccm01-airag.tfvars`). There is no settings change. Promoting scoring is optional (byte-identical).
- Before release, run the harness on a retrieval snapshot to baseline `invalid` and `trimmed` rates per model.
- After deployment, rerun ragEval and watch the guard WARNs, `invalid=` in the reason column and the partial-invalidity WARN on dev and ste.
- Rollback is to promote the previous retrieval `version`. There is no data migration.
