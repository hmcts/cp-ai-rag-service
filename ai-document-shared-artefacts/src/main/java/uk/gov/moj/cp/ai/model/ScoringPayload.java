package uk.gov.moj.cp.ai.model;

import java.util.List;

public record ScoringPayload(
        String userQuery,
        String llmResponse,
        String queryPrompt,
        List<ChunkedEntry> chunkedEntries,
        String transactionId
) {
}
