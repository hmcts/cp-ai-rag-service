package uk.gov.moj.cp.ai.model;

public record QueueTaskResult(boolean success, String messageId, String errorMessage) {

}