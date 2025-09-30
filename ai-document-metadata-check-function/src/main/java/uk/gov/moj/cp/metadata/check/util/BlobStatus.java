package uk.gov.moj.cp.metadata.check.util;

public enum BlobStatus {
    INGESTION_SUCCESS("Document Ingestion Success"),
    INGESTION_FAILED("Document Ingestion Failed"),
    METADATA_MISSING("Document is required but not found"),
    MANDATORY_DOCUMENT_ID("Document ID is required but not found"),
    INVALID_DOCUMENT_ID("Invalid document_id format"),
    UNKNOWN_FAILURE("UNKNOWN_FAILURE"),
    BLOB_NOT_FOUND("Blob does not exist");

    private final String reason;

    BlobStatus(final String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
