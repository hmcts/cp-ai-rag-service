package uk.gov.moj.cp.scoring.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ModelScore {

    @JsonProperty("groundedness_score")
    private Double score;

    @JsonProperty("reasoning")
    private String reasoning;

    public ModelScore() {
    }

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
