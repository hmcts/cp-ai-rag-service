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
                                     String currentTimestamp) {
}
