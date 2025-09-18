package uk.gov.moj.cp.scoring.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QueryResponse(
        @JsonProperty("userQuery") String userQuery,
        @JsonProperty("llmResponse") String llmResponse,
        @JsonProperty("queryPrompt") String queryPrompt,
        @JsonProperty("chunkedEntries") List<ChunkedEntry> chunkedEntries
) {
}
