package uk.gov.moj.cp.ai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueueIngestionMetadataTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Tolerates the removed isDocumentIdUsedAsRowKey field on legacy in-flight messages")
    void deserializesLegacyMessageContainingRemovedField() throws Exception {
        // A message enqueued before the field was removed (e.g. still on the queue during a rollout).
        final String legacyJson = """
                {
                  "documentId": "123e4567-e89b-12d3-a456-426614174000",
                  "documentName": "Contract-Agreement.pdf",
                  "metadata": {"document_type": "CONTRACT"},
                  "blobUrl": "https://storage.blob.core.windows.net/legal/Contract-Agreement.pdf",
                  "currentTimestamp": "2025-10-07T10:30:45.123456Z",
                  "isDocumentIdUsedAsRowKey": true
                }
                """;

        // Plain mapper => FAIL_ON_UNKNOWN_PROPERTIES is at its enabled default; only the record's
        // @JsonIgnoreProperties(ignoreUnknown = true) lets the unknown legacy field through.
        final QueueIngestionMetadata metadata = objectMapper.readValue(legacyJson, QueueIngestionMetadata.class);

        assertEquals("123e4567-e89b-12d3-a456-426614174000", metadata.documentId());
        assertEquals("Contract-Agreement.pdf", metadata.documentName());
        assertEquals("CONTRACT", metadata.metadata().get("document_type"));
        assertEquals("https://storage.blob.core.windows.net/legal/Contract-Agreement.pdf", metadata.blobUrl());
        assertEquals("2025-10-07T10:30:45.123456Z", metadata.currentTimestamp());
    }

    @Test
    @DisplayName("Round-trips a current message without the removed field")
    void roundTripsCurrentMessage() throws Exception {
        final QueueIngestionMetadata original = new QueueIngestionMetadata(
                "123e4567-e89b-12d3-a456-426614174000",
                "Contract-Agreement.pdf",
                null,
                "https://storage.blob.core.windows.net/legal/Contract-Agreement.pdf",
                "2025-10-07T10:30:45.123456Z");

        final String json = objectMapper.writeValueAsString(original);
        final QueueIngestionMetadata roundTripped = objectMapper.readValue(json, QueueIngestionMetadata.class);

        assertEquals(original, roundTripped);
        assertNull(roundTripped.metadata());
    }
}
