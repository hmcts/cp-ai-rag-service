package uk.gov.moj.cp.ai.model;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MTDI-02 (AC-012) backward-compatibility specs for the additive {@code clientId} field on
 * {@link ScoringPayload}. Existing producers (unchanged in this story) still serialise correctly with
 * {@code clientId} simply absent/null. Pure-data AC — passes on the additive skeleton.
 */
class ScoringPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("AC-012: a payload built by the existing 5-arg producers serialises with clientId null and round-trips")
    void shouldSerialiseWithNullClientIdAndRoundTrip_whenBuiltByLegacyProducer() throws Exception {
        final ScoringPayload original = new ScoringPayload(
                "user query", "llm response", "query prompt", List.of(), "12345");

        assertNull(original.clientId());

        final String json = objectMapper.writeValueAsString(original);
        final ScoringPayload roundTripped = objectMapper.readValue(json, ScoringPayload.class);

        assertNull(roundTripped.clientId());
        assertEquals(original, roundTripped);
    }

    @Test
    @DisplayName("AC-012: a payload carrying clientId round-trips the value through JSON")
    void shouldRoundTripClientId_whenSet() throws Exception {
        final String clientId = randomUUID().toString();
        final ScoringPayload original = new ScoringPayload(
                "user query", "llm response", "query prompt", List.of(), "12345", clientId);

        final String json = objectMapper.writeValueAsString(original);
        final ScoringPayload roundTripped = objectMapper.readValue(json, ScoringPayload.class);

        assertEquals(clientId, roundTripped.clientId());
        assertEquals(original, roundTripped);
    }
}
