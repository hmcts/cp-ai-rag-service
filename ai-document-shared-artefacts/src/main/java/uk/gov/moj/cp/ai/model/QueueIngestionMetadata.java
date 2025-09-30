package uk.gov.moj.cp.ai.model;

import java.util.Map;

public record QueueIngestionMetadata(String documentId,
                                     String documentName,
                                     Map<String, String> additionalMetadata,
                                     String blobUrl,
                                     String currentTimestamp ) {}
