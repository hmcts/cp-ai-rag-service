package uk.gov.moj.cp.ai.model;

import java.util.List;
import java.util.UUID;

public record AnswerGenerationQueuePayload(
        UUID transactionId,
        String userQuery,
        String userQueryPrompt,
        List<KeyValuePair> metadataFilters
) {
}
