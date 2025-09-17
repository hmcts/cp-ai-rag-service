package uk.gov.moj.cp.scoring.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class QueryResponse {

    @JsonProperty("userQuery")
    private String userQuery;

    @JsonProperty("llmResponse")
    private String llmResponse;

    @JsonProperty("queryPrompt")
    private String queryPrompt;

    @JsonProperty("chunkedEntries")
    private List<ChunkedEntry> chunkedEntries;

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(final String userQuery) {
        this.userQuery = userQuery;
    }

    public String getLlmResponse() {
        return llmResponse;
    }

    public void setLlmResponse(final String llmResponse) {
        this.llmResponse = llmResponse;
    }

    public String getQueryPrompt() {
        return queryPrompt;
    }

    public void setQueryPrompt(final String queryPrompt) {
        this.queryPrompt = queryPrompt;
    }

    public List<ChunkedEntry> getChunkedEntries() {
        return chunkedEntries;
    }

    public void setChunkedEntries(final List<ChunkedEntry> chunkedEntries) {
        this.chunkedEntries = chunkedEntries;
    }


}
