package uk.gov.moj.cp.ai.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The answer-scoring blob: everything the scorer needs to judge groundedness, plus the
 * correlation fields that tie the scoring run back to the request that produced it.
 *
 * <p>Fields are only ever added, nullable and kept last, so an older scorer can read a newer
 * blob ({@code ignoreUnknown}) and a newer scorer can read an older one (absent → null).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoringPayload(
        String userQuery,
        String llmResponse,
        String queryPrompt,
        List<ChunkedEntry> chunkedEntries,
        String transactionId,
        // Additive client-scoping field. Nullable; producers set it when adopted.
        String clientId,
        // Additive correlation field (DD-43721). The synchronous answer endpoint has no transaction,
        // so it sets this to its own Functions invocation ID; the scorer puts it in its log context
        // as "origin=" so the request's and the scoring run's lines read as one journey. Null on the
        // async path, where transactionId already covers the hop.
        String originInvocationId
) {
    public ScoringPayload(String userQuery, String llmResponse, String queryPrompt,
                          List<ChunkedEntry> chunkedEntries, String transactionId) {
        this(userQuery, llmResponse, queryPrompt, chunkedEntries, transactionId, null, null);
    }

    public ScoringPayload(String userQuery, String llmResponse, String queryPrompt,
                          List<ChunkedEntry> chunkedEntries, String transactionId, String clientId) {
        this(userQuery, llmResponse, queryPrompt, chunkedEntries, transactionId, clientId, null);
    }
}
