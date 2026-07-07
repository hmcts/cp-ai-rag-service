package uk.gov.moj.cp.retrieval.model;

import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;

/**
 * Internal result of answer generation. {@code reason} is nullable and set only when the
 * citation guard intervened (rejected or delivered a citation-degraded answer) — it feeds
 * the async table's reason column and logs; it is not part of the HTTP contract.
 */
public record LlmResponse (
        String rawLlmResponse,
        String formattedLlmResponse,
        AnswerGenerationStatus status,
        String reason
){
    public LlmResponse(final String rawLlmResponse, final String formattedLlmResponse,
                       final AnswerGenerationStatus status) {
        this(rawLlmResponse, formattedLlmResponse, status, null);
    }
}
