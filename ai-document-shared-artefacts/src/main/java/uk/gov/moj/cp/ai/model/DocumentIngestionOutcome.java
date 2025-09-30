package uk.gov.moj.cp.ai.model;


import java.time.LocalDate;

import com.azure.data.tables.models.TableEntity;

public class DocumentIngestionOutcome {
    private String documentName;
    private String reason;
    private String documentId;
    private String status;
    private String blobUrl;
    private String timestamp;

    public DocumentIngestionOutcome() {
        // Empty constructor required for:
        // 1. Java Bean specification compliance for serialization/deserialization
        // 2. Azure Table Storage entity mapping
        // 3. Object instantiation before property population via setters
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(final String documentName) {
        this.documentName = documentName;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(final String reason) {
        this.reason = reason;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getStatus() {
        return status;
    }

    public String getBlobUrl() {
        return blobUrl;
    }

    public void setDocumentId(final String documentId) {
        this.documentId = documentId;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public void setBlobUrl(final String blobUrl) {
        this.blobUrl = blobUrl;
    }

    public void setTimestamp(final String timestamp) {
        this.timestamp = timestamp;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public TableEntity toTableEntity() {
        String partitionKey = LocalDate.now().toString().replace("-", "");
        TableEntity entity = new TableEntity(partitionKey, documentId);
        entity.addProperty("documentName", documentName);
        entity.addProperty("documentId", documentId);
        entity.addProperty("status", status);
        entity.addProperty("reason", reason);
        entity.addProperty("timestamp", timestamp);
        entity.addProperty("blobUrl", blobUrl);
        entity.addProperty("timestamp", timestamp);
        return entity;
    }
}
