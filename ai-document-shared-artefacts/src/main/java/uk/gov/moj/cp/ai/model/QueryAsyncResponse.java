package uk.gov.moj.cp.ai.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryAsyncResponse(
        String transactionId,
        String status,
        String reason,
        String userQuery,
        String llmResponse,
        String queryPrompt,
        List<ChunkedEntry> chunkedEntries,
        String responseGenerationTime,
        Long responseGenerationDuration
) {
    public QueryAsyncResponse(final String transactionId, final String status, final String reason, final String userQuery,
                              final String llmResponse, final String queryPrompt, final List<ChunkedEntry> chunkedEntries,
                              final String responseGenerationTime, final Long responseGenerationDuration) {
        this.transactionId = transactionId;
        this.status = status;
        this.reason = reason;
        this.userQuery = userQuery;
        this.llmResponse = llmResponse;
        this.queryPrompt = queryPrompt;
        this.chunkedEntries = chunkedEntries;
        this.responseGenerationTime = responseGenerationTime;
        this.responseGenerationDuration = responseGenerationDuration;
    }

    public QueryAsyncResponse(final String transactionId) {
        this(transactionId, null, null, null, null, null,
                null, null, null);
    }

}
