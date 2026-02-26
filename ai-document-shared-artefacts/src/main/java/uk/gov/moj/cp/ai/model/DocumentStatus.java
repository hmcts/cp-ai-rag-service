package uk.gov.moj.cp.ai.model;

public enum DocumentStatus {
    INGESTION_SUCCESS("Document ingestion completed successfully"),
    INGESTION_FAILED("Document ingestion failed during processing"),

    METADATA_VALIDATED("Document metadata validated and sent to queue"),
    INVALID_METADATA("Invalid or incomplete nested metadata detected"),
    AWAITING_UPLOAD("Upload initiated, document awaiting upload"),

    QUEUE_FAILED("Document metadata validated but failed to enqueue for processing"),

    BLOB_NOT_FOUND("Blob does not exist in storage");

    private final String reason;

    DocumentStatus(final String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
