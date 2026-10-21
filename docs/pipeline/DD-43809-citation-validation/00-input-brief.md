# DD-43809 — Validate LLM citations against the retrieved chunks

## Problem

The documentId in a rendered citation comes from the LLM's `FACT_MAP_JSON` entry.
`CitationProcessor` (ai-document-answer-retrieval-function) formats it as-is via
`CITATION_FORMAT` without checking it against the chunks that were actually retrieved.
A model typo yields a citation that points at no document — a broken link in the
consuming UI. It is not a cross-document leak: the file name and pages stay correct.

## Evidence

ragEval run on dev01, 2026-10-09 15:19. A test document ingested as
`16a09c64-52e4-4dd7-a106-32c55e71f8fa`. Two queries each returned one citation with
`documentId=16a09c64-52e4-4dd7-a106-32c55e71f8` (last two characters dropped), alongside
correct citations to the same file. Intermittent — earlier runs of the same queries passed.

## Gaps found in the current code (main @ 6586408c)

1. `ResponseGenerationService.generateResponse` holds the retrieved `chunkedEntries` but calls
   `citationProcessor.processCitations(raw)` with the raw text only — nothing can validate.
2. `CitationProcessor.substituteCitation` / `formatMergedCitation` render `documentId`,
   `documentFilename` and pages straight from the model's JSON; missing fields fall back to
   `UNKNOWN_ID` / `UNKNOWN_FILE` (also broken links).
3. `mergeSameDocumentRuns` groups adjacent markers by the model's raw documentId, so a garbled id
   splits a run that should merge.
4. `countRenderedCitations` counts every rendered citation, so an answer whose citations are all
   invalid still passes `ResponseGenerationService.isAcceptable`.
5. The filename the model sees is the `material_name` custom-metadata value when present, else
   `documentFileName` (`ChunkFormatterUtility.extractMaterialName`, private).
6. The offline harness (`CitationMetrics`) does not detect invalid documentIds.

## Agreed behaviour (decided with the product owner, 2026-10-20)

- **No repair.** Each `FACT_MAP_JSON` entry is validated against the retrieved chunks:
  - `documentId` — exact match to a retrieved chunk's documentId (also proves it is not hallucinated);
  - `documentFilename` — trimmed, case-insensitive match to the filename the model was shown for
    that document (`material_name` metadata, else `documentFileName`);
  - pages — each page in `individualPageNumbers` must be a page retrieved for that document.
- **Invalid pages are trimmed**; the citation is kept if at least one page survives.
- An entry with a missing/mismatched documentId or filename, or no surviving page, is **dropped**:
  its inline marker becomes unresolved and is stripped by the existing logic.
  The `UNKNOWN_ID` / `UNKNOWN_FILE` fallbacks are removed.
- A kept citation renders the documentId and filename **from retrieval**, not the model's copy.
- Validation happens **before** same-document run merging and substitution.
- `emptyFactMap` (deliberate no-evidence refusal) is computed from the **original** parsed list,
  so "every entry invalid" is not mistaken for a refusal.
- **No valid citation left → citation-degraded** (existing guard path: async retry via redelivery,
  then deliver/reject; sync applies the policy immediately). The guard reason gains an
  `invalid=N` count.
- **Partial invalidity** (some entries dropped, at least one citation rendered) → answer accepted
  as `ANSWER_GENERATED`, **WARN log only — no reason column**. Rationale: the downstream ragEval
  suite treats any non-blank reason on an `ANSWER_GENERATED` answer as citation-degraded
  (gate `maxCitationDegradedRate` = 0.0), so the reason column stays reserved for real degradation.

## Out of scope

- Repairing garbled ids (prefix / edit-distance / filename lookup).
- The `isAcceptable` loophole where a long uncited answer with an empty `[]` passes as a refusal
  (tracked under DD-43838).
- API contract changes — none needed (citation string format unchanged).
