package uk.gov.moj.cp.ai.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ingestion queue message. {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps deserialization
 * tolerant of fields present on older messages (e.g. the removed {@code isDocumentIdUsedAsRowKey}
 * flag) so in-flight messages enqueued before a rollout still deserialize cleanly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueueIngestionMetadata(String documentId,
                                     String documentName,
                                     Map<String, String> metadata,
                                     String blobUrl,
                                     String currentTimestamp,
                                     // Additive client-scoping field, kept last. Nullable; legacy
                                     // messages without it deserialize with clientId == null.
                                     String clientId) {

    /**
     * Backward-compatible constructor for producers pre-dating the additive {@code clientId} field;
     * {@code clientId} defaults to {@code null}.
     */
    public QueueIngestionMetadata(String documentId, String documentName, Map<String, String> metadata,
                                  String blobUrl, String currentTimestamp) {
        this(documentId, documentName, metadata, blobUrl, currentTimestamp, null);
    }
}
