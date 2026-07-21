package uk.gov.moj.cp.retrieval.model;


import java.util.List;
import java.util.UUID;
import uk.gov.moj.cp.ai.model.KeyValuePair;


public record AnswerGenerationQueuePayload(
        UUID transactionId,
        String userQuery,
        String queryPrompt,
        List<KeyValuePair> metadataFilter,
        // Additive client-scoping field, kept last. Nullable; legacy messages without it
        // deserialize with clientId == null. Set by InitiateAnswerGenerationFunction when adopted.
        String clientId
) {

    /**
     * Backward-compatible constructor for producers pre-dating the additive {@code clientId} field;
     * {@code clientId} defaults to {@code null}.
     */
    public AnswerGenerationQueuePayload(UUID transactionId, String userQuery, String queryPrompt,
                                        List<KeyValuePair> metadataFilter) {
        this(transactionId, userQuery, queryPrompt, metadataFilter, null);
    }
}