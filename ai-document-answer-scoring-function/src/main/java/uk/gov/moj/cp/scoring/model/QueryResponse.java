package uk.gov.moj.cp.scoring.model;

import java.util.List;

public record QueryResponse(
        String userQuery,
        String llmResponse,
        String queryPrompt,
        List<ChunkedEntry> chunkedEntries
) {
}
