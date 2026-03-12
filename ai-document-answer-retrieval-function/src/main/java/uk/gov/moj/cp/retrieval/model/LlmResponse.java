package uk.gov.moj.cp.retrieval.model;

import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;

public record LlmResponse (
        String rawLlmResponse,
        String formattedLlmResponse,
        AnswerGenerationStatus status
){
}
