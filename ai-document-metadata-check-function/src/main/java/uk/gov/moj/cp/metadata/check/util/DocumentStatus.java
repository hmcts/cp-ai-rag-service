package uk.gov.moj.cp.metadata.check.util;

public enum DocumentStatus {
    INGESTION_SUCCESS("Document ingestion completed successfully"),
    INGESTION_FAILED("Document ingestion failed during processing"),

    METADATA_VALIDATED("Document metadata validated and sent to queue"),
    INVALID_METADATA("Invalid or incomplete nested metadata detected"),

    QUEUE_FAILED("Document metadata validated but failed to enqueue for processing"),

    MANDATORY_DOCUMENT_ID_MISSING("Document ID is required but missing from metadata"),
    INVALID_DOCUMENT_ID("Document ID is not in a valid UUID format"),

    BLOB_NOT_FOUND("Blob does not exist in storage");

    private final String reason;

    DocumentStatus(final String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
