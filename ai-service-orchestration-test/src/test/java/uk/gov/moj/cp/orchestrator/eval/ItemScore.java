package uk.gov.moj.cp.orchestrator.eval;

import java.util.List;

/**
 * The measures for one item in one run (design D4). Measures that do not apply to the item are
 * null: fact and gold-page measures on a should-refuse item, citation precision without
 * citations, section counts unless the item asks for a citation in every section, retrieval
 * checks unless the item defines them.
 *
 * @param factRecall             M6: share of expected facts stated in the answer prose
 * @param factRecallPass         M6 against the item threshold ({@code minFactRecall}, default 1.0)
 * @param forbiddenHits          M7: forbidden facts or texts present in the answer
 * @param refusalCorrect         M8: refused exactly when the item should refuse
 * @param goldPageRecall         M3: share of expected facts with at least one gold page in the final chunks
 * @param retrievalOk            M4: the item's document-level retrieval checks hold
 * @param chunksOutsideFilter    M5: chunks whose metadata does not satisfy the item's filter
 * @param citationPrecision      M9: share of cited pages that are gold pages
 * @param citationPrecisionGated false for long-answer items, where M9 is report-only
 * @param citationDegraded       M10: the service recorded a citation guard reason
 */
public record ItemScore(String id,
                        String status,
                        double elapsedSeconds,
                        Double factRecall,
                        Boolean factRecallPass,
                        List<String> missingFacts,
                        List<String> forbiddenHits,
                        boolean refused,
                        boolean refusalCorrect,
                        Double goldPageRecall,
                        Boolean retrievalOk,
                        List<String> retrievedDocuments,
                        int chunksOutsideFilter,
                        int citedPages,
                        Double citationPrecision,
                        boolean citationPrecisionGated,
                        Integer sections,
                        Integer sectionsWithoutCitation,
                        boolean citationDegraded,
                        String reason,
                        int chunks,
                        int answerChars) {
}
