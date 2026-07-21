package uk.gov.moj.cp.ai.model;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MTDI-02 (AC-009) backward-compatibility specs for the additive {@code clientId} field on
 * {@link ChunkedEntry}. Pure-data ACs — these pass on the additive skeleton (default Jackson behaviour).
 */
class ChunkedEntryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("AC-009: a chunk built without clientId (today's callers) defaults clientId to null and round-trips")
    void shouldDefaultClientIdToNullAndRoundTrip_whenBuiltWithoutClientId() throws Exception {
        final ChunkedEntry original = ChunkedEntry.builder()
                .id(randomUUID().toString())
                .documentId(randomUUID().toString())
                .chunk("some content")
                .chunkVector(List.of(0.1f, 0.2f, 0.3f))
                .documentFileName("file.pdf")
                .pageNumber(1)
                .chunkIndex(0)
                .documentFileUrl("https://storage/file.pdf")
                .customMetadata(List.of())
                .build();

        assertNull(original.clientId());

        final String json = objectMapper.writeValueAsString(original);
        final ChunkedEntry roundTripped = objectMapper.readValue(json, ChunkedEntry.class);

        assertEquals(original, roundTripped);
        assertNull(roundTripped.clientId());
    }

    @Test
    @DisplayName("AC-009: a chunk built with clientId round-trips the value through JSON")
    void shouldRoundTripClientId_whenBuiltWithClientId() throws Exception {
        final String clientId = randomUUID().toString();
        final ChunkedEntry original = ChunkedEntry.builder()
                .id(randomUUID().toString())
                .documentId(randomUUID().toString())
                .chunk("some content")
                .chunkVector(List.of(0.1f, 0.2f, 0.3f))
                .documentFileName("file.pdf")
                .pageNumber(1)
                .chunkIndex(0)
                .documentFileUrl("https://storage/file.pdf")
                .customMetadata(List.of())
                .clientId(clientId)
                .build();

        assertEquals(clientId, original.clientId());

        final String json = objectMapper.writeValueAsString(original);
        final ChunkedEntry roundTripped = objectMapper.readValue(json, ChunkedEntry.class);

        assertEquals(clientId, roundTripped.clientId());
        assertEquals(original, roundTripped);
    }

    @Test
    @DisplayName("AC-009: legacy JSON without a clientId property deserialises with clientId == null")
    void shouldDeserialiseLegacyJsonWithNullClientId() throws Exception {
        final String legacyJson = """
                {
                  "id": "123e4567-e89b-12d3-a456-426614174000",
                  "documentId": "223e4567-e89b-12d3-a456-426614174000",
                  "chunk": "content",
                  "documentFileName": "file.pdf",
                  "pageNumber": 1,
                  "chunkIndex": 0,
                  "documentFileUrl": "https://storage/file.pdf"
                }
                """;

        final ChunkedEntry entry = objectMapper.readValue(legacyJson, ChunkedEntry.class);

        assertNull(entry.clientId());
        assertEquals("123e4567-e89b-12d3-a456-426614174000", entry.id());
    }
}
