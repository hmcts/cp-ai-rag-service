package uk.gov.moj.cp.ai.model;

import java.util.Map;

public record BlobMetadata(String documentId,
                           Map<String, String> additionalMetadata,
                           String blobUrl,
                           String currentTimestamp ) {}
