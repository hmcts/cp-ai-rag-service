package uk.gov.moj.cp.orchestrator.eval;

import java.util.List;

/**
 * What the service returned for one query: the async status response reduced to the fields the
 * scorer reads, plus the client-side time to complete.
 *
 * @param reason the citation guard reason, null when the answer was not degraded
 */
public record AnswerResult(String status, String llmResponse, String reason, List<RetrievedChunk> chunks,
                           double elapsedSeconds) {

    /** A chunk sent to the model, identified by document and page, with the metadata it was indexed with. */
    public record RetrievedChunk(String documentId, int pageNumber, List<MetadataEntry> metadata) {
    }
}
