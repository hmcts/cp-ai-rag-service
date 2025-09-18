package uk.gov.moj.cp.scoring.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChunkedEntry(
        @JsonProperty("documentFileName") String documentFileName,
        @JsonProperty("chunk") String chunk,
        @JsonProperty("pageNumber") Integer pageNumber,
        @JsonProperty("score") double score
) {
}
