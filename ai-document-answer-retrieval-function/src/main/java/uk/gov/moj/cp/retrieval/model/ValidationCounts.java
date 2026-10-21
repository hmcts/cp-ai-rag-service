package uk.gov.moj.cp.retrieval.model;

/**
 * Validation counts for one answer: invalid entries by cause (one cause each, see
 * {@link uk.gov.moj.cp.retrieval.service.CitationEntryValidator}) and pages trimmed from kept ones.
 */
public record ValidationCounts(int factMapEntries, int invalidCitationId, int invalidDocumentId,
                               int invalidFileName, int noValidPage, int trimmedPages) {

    public static final ValidationCounts NONE = new ValidationCounts(0, 0, 0, 0, 0, 0);

    public int invalidEntries() {
        return invalidCitationId + invalidDocumentId + invalidFileName + noValidPage;
    }
}
