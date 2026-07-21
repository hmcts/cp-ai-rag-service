package uk.gov.moj.cp.ai.model;

import java.util.List;

public record ScoringPayload(
        String userQuery,
        String llmResponse,
        String queryPrompt,
        List<ChunkedEntry> chunkedEntries,
        String transactionId,
        // Additive client-scoping field, kept last. Nullable; producers set it when adopted.
        String clientId
) {

    /**
     * Backward-compatible constructor for producers pre-dating the additive {@code clientId} field;
     * {@code clientId} defaults to {@code null}.
     */
    public ScoringPayload(String userQuery, String llmResponse, String queryPrompt,
                          List<ChunkedEntry> chunkedEntries, String transactionId) {
        this(userQuery, llmResponse, queryPrompt, chunkedEntries, transactionId, null);
    }
}
