package uk.gov.moj.cp.retrieval.model;

import java.util.List;

public record RequestPayload(
        String userQuery,
        String queryPrompt,
        List<KeyValuePair> metadataFilter
) {
}