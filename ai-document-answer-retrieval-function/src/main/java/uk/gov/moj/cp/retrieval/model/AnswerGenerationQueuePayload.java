package uk.gov.moj.cp.retrieval.model;


import java.util.List;
import java.util.UUID;
import uk.gov.moj.cp.ai.model.KeyValuePair;


public record AnswerGenerationQueuePayload(
        UUID transactionId,
        String userQuery,
        String queryPrompt,
        List<KeyValuePair> metadataFilter
) {}