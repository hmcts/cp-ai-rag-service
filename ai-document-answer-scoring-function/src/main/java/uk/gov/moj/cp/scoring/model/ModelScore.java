package uk.gov.moj.cp.scoring.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ModelScore {

    private static final String SCORE = "groundedness_score";
    private static final String REASONING = "reasoning";

    @JsonProperty(SCORE)
    private Double score;

    @JsonProperty(REASONING)
    private String reasoning;

    public ModelScore() {}

    public ModelScore(final Double score, final String reasoning) {
        this.score = score;
        this.reasoning = reasoning;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(final Double score) {
        this.score = score;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(final String reasoning) {
        this.reasoning = reasoning;
    }
}
