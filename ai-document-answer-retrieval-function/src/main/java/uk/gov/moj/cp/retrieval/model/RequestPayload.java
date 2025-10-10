package uk.gov.moj.cp.retrieval.model;

import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.List;

public record RequestPayload(
        String userQuery,
        String queryPrompt,
        List<KeyValuePair> metadataFilter
) {
}