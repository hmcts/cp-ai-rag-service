package uk.gov.moj.cp.ai.model;

import java.time.OffsetDateTime;

public class DocumentIngestionOutcome extends BaseTableEntity {

    private String documentId;
    private String documentName;
    private String status;
    private String reason;
    private String timestamp;

    public DocumentIngestionOutcome() {
        // required for Azure Functions Table binding
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public static DocumentIngestionOutcome build(String documentId,
                                                 String documentName,
                                                 String status,
                                                 String reason) {
        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome();
        outcome.setDocumentId(documentId);
        outcome.setDocumentName(documentName);
        outcome.setStatus(status);
        outcome.setReason(reason);
        outcome.setTimestamp(OffsetDateTime.now().toString());
        outcome.generateDefaultPartitionKey();
        outcome.generateRowKeyFrom(documentName != null ? documentName : documentId);
        return outcome;
    }
}