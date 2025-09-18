package uk.gov.moj.cp.scoring.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModelScore(
        @JsonProperty("groundedness_score") double score,
        @JsonProperty("reasoning") String reasoning
) {}
