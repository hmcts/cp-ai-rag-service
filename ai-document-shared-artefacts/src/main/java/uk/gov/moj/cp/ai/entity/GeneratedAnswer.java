package uk.gov.moj.cp.ai.entity;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.time.OffsetDateTime;
import java.util.List;

public class GeneratedAnswer extends BaseTableEntity {

    private String transactionId;

    private String userQuery;

    private String queryPrompt;

    private List<ChunkedEntry> chunkedEntries;

    private String llmResponse;
    private String answerStatus;
    private String reason;
    private OffsetDateTime responseGenerationTime;
    private Long responseGenerationDuration;


    public GeneratedAnswer() {
        // required for Azure Functions Table binding
    }

    public GeneratedAnswer(final String transactionId,
                           final String userQuery,
                           final String queryPrompt,
                           final List<ChunkedEntry> chunkedEntries,
                           final String llmResponse,
                           final String answerStatus,
                           final String reason,
                           final OffsetDateTime responseGenerationTime,
                           final Long responseGenerationDuration) {
        this.transactionId = transactionId;
        this.userQuery = userQuery;
        this.queryPrompt = queryPrompt;
        this.chunkedEntries = chunkedEntries;
        this.llmResponse = llmResponse;
        this.answerStatus = answerStatus;
        this.reason = reason;
        this.responseGenerationTime = responseGenerationTime;
        this.responseGenerationDuration = responseGenerationDuration;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(final String transactionId) {
        this.transactionId = transactionId;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(final String userQuery) {
        this.userQuery = userQuery;
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

    public String getLlmResponse() {
        return llmResponse;
    }

    public void setLlmResponse(final String llmResponse) {
        this.llmResponse = llmResponse;
    }

    public String getAnswerStatus() {
        return answerStatus;
    }

    public void setAnswerStatus(final String answerStatus) {
        this.answerStatus = answerStatus;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(final String reason) {
        this.reason = reason;
    }

    public OffsetDateTime getResponseGenerationTime() {
        return responseGenerationTime;
    }

    public void setResponseGenerationTime(final OffsetDateTime responseGenerationTime) {
        this.responseGenerationTime = responseGenerationTime;
    }

    public Long getResponseGenerationDuration() {
        return responseGenerationDuration;
    }

    public void setResponseGenerationDuration(final Long responseGenerationDuration) {
        this.responseGenerationDuration = responseGenerationDuration;
    }
}