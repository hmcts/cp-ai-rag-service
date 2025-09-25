package uk.gov.moj.cp.common.model;

import java.util.Map;

/**
 * POJO representing blob metadata for queue processing.
 */
public record BlobMetadata(
        String documentId,
        Map<String, String> additionalMetadata,
        String blobUrl,
        String currentTimestamp
) {}
