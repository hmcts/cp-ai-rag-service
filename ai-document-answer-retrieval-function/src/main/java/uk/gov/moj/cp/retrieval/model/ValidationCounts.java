package uk.gov.moj.cp.retrieval.model;

/**
 * Entry-level counts from validating the {@code <FACT_MAP_JSON>} entries against the retrieved
 * chunks. Each invalid entry carries exactly one cause (the first failing check), so
 * {@link #invalidEntries()} is the sum of the four cause counts.
 *
 * @param factMapEntries    entries in the parsed list (0 when there was no parseable block)
 * @param invalidCitationId entries dropped for a missing, blank or non-numeric {@code citationId}
 * @param invalidDocumentId entries dropped because the documentId named no retrieved document
 * @param invalidFileName   entries dropped because the filename did not match that document
 * @param noValidPage       entries dropped because none of their pages was retrieved
 * @param trimmedPages      page tokens removed from the entries that were kept
 */
public record ValidationCounts(int factMapEntries, int invalidCitationId, int invalidDocumentId,
                               int invalidFileName, int noValidPage, int trimmedPages) {

    /** No entries were validated: no block, unparseable JSON, or empty input. */
    public static final ValidationCounts NONE = new ValidationCounts(0, 0, 0, 0, 0, 0);

    /** Entries (not markers, not ids) that failed validation. */
    public int invalidEntries() {
        return invalidCitationId + invalidDocumentId + invalidFileName + noValidPage;
    }
}
