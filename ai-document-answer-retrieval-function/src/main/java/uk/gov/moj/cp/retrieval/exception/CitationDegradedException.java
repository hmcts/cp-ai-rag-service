package uk.gov.moj.cp.retrieval.exception;

/**
 * Thrown by the citation guard when a generated answer is citation-degraded — no
 * {@code <FACT_MAP_JSON>} block, unparseable citation JSON, or every inline marker stripped as
 * unresolved — and is therefore not fit to progress as-is.
 *
 * <p>Unchecked by design: on the async queue path it propagates through the worker's generic
 * exception handling so the message is redelivered (a fresh end-to-end attempt) until
 * {@code maxDequeueCount}; the exhaustion policy ({@code CitationGuardMode}) is then applied by
 * the caller. The exception carries the degraded answer so a {@code DELIVER}-mode caller can
 * still return it without regenerating.
 */
public class CitationDegradedException extends RuntimeException {

    private final transient String rawLlmResponse;
    private final transient String formattedText;

    public CitationDegradedException(final String reason, final String rawLlmResponse, final String formattedText) {
        super(reason);
        this.rawLlmResponse = rawLlmResponse;
        this.formattedText = formattedText;
    }

    public String rawLlmResponse() {
        return rawLlmResponse;
    }

    public String formattedText() {
        return formattedText;
    }
}
