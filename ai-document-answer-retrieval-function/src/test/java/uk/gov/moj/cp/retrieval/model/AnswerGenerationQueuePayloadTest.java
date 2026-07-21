package uk.gov.moj.cp.retrieval.model;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Backward-compatibility specs for the additive {@code clientId} field on
 * {@link AnswerGenerationQueuePayload}. A legacy/pre-rollout message without a {@code clientId}
 * property must deserialise with {@code clientId == null}; a message carrying it must round-trip.
 * Uses the production {@code ObjectMapperFactory} to mirror the worker's deserialisation path.
 */
class AnswerGenerationQueuePayloadTest {

    private final ObjectMapper objectMapper = getObjectMapper();

    @Test
    @DisplayName("a legacy message without a clientId property deserialises with clientId == null")
    void shouldDeserialiseLegacyShapeWithNullClientId() throws Exception {
        final String legacyJson = """
                {
                  "transactionId": "123e4567-e89b-12d3-a456-426614174000",
                  "userQuery": "what is the deadline?",
                  "queryPrompt": "answer strictly from context",
                  "metadataFilter": []
                }
                """;

        final AnswerGenerationQueuePayload payload =
                objectMapper.readValue(legacyJson, AnswerGenerationQueuePayload.class);

        assertNull(payload.clientId());
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), payload.transactionId());
    }

    @Test
    @DisplayName("a message carrying clientId round-trips the value through JSON")
    void shouldRoundTripClientId_whenSet() throws Exception {
        final String clientId = randomUUID().toString();
        final AnswerGenerationQueuePayload original = new AnswerGenerationQueuePayload(
                randomUUID(), "what is the deadline?", "answer strictly from context", List.of(), clientId);

        final String json = objectMapper.writeValueAsString(original);
        final AnswerGenerationQueuePayload roundTripped =
                objectMapper.readValue(json, AnswerGenerationQueuePayload.class);

        assertEquals(clientId, roundTripped.clientId());
        assertEquals(original, roundTripped);
    }

    @Test
    @DisplayName("the legacy 4-arg constructor defaults clientId to null")
    void shouldDefaultClientIdToNull_whenBuiltByLegacyConstructor() {
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                randomUUID(), "query", "prompt", List.of());

        assertNull(payload.clientId());
    }
}
