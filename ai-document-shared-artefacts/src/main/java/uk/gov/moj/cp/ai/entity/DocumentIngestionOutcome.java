package uk.gov.moj.cp.ai.entity;

import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TIMESTAMP;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DocumentIngestionOutcome extends BaseTableEntity {

    @JsonProperty(TC_DOCUMENT_ID)
    private String documentId;

    @JsonProperty(TC_DOCUMENT_FILE_NAME)
    private String documentName;

    @JsonProperty(TC_DOCUMENT_STATUS)
    private String status;

    @JsonProperty(TC_REASON)
    private String reason;

    @JsonProperty(TC_TIMESTAMP)
    private String timestamp;

    public DocumentIngestionOutcome() {
        // required for Azure Functions Table binding
    }

    public DocumentIngestionOutcome(final String documentId,
                                    final String documentName,
                                    final String status,
                                    final String reason,
                                    final String timestamp) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.status = status;
        this.reason = reason;
        this.timestamp = timestamp;
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
}