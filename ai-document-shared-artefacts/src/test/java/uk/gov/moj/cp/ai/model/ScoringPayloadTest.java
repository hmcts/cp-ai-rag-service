package uk.gov.moj.cp.ai.model;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Backward-compatibility specs for the additive {@code clientId} and
 * {@code originInvocationId} fields on {@link ScoringPayload}. Existing producers still
 * serialise correctly with the fields simply absent/null, and a blob carrying a field this
 * reader does not know is still readable.
 */
class ScoringPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("a payload built by the existing 5-arg producers serialises with clientId null and round-trips")
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
    @DisplayName("a payload carrying clientId round-trips the value through JSON")
    void shouldRoundTripClientId_whenSet() throws Exception {
        final String clientId = randomUUID().toString();
        final ScoringPayload original = new ScoringPayload(
                "user query", "llm response", "query prompt", List.of(), "12345", clientId);

        final String json = objectMapper.writeValueAsString(original);
        final ScoringPayload roundTripped = objectMapper.readValue(json, ScoringPayload.class);

        assertEquals(clientId, roundTripped.clientId());
        assertNull(roundTripped.originInvocationId());
        assertEquals(original, roundTripped);
    }

    @Test
    @DisplayName("the synchronous producer's originInvocationId round-trips through JSON")
    void shouldRoundTripOriginInvocationId_whenSet() throws Exception {
        final String invocationId = randomUUID().toString();
        final ScoringPayload original = new ScoringPayload(
                "user query", "llm response", "query prompt", List.of(), null, null, invocationId);

        final String json = objectMapper.writeValueAsString(original);
        final ScoringPayload roundTripped = objectMapper.readValue(json, ScoringPayload.class);

        assertEquals(invocationId, roundTripped.originInvocationId());
        assertNull(roundTripped.transactionId());
        assertEquals(original, roundTripped);
    }

    @Test
    @DisplayName("a legacy blob written before the field existed deserialises with originInvocationId null")
    void shouldDeserialiseLegacyBlob_withoutOriginInvocationId() throws Exception {
        final String legacyJson = "{\"userQuery\":\"q\",\"llmResponse\":\"a\",\"queryPrompt\":\"p\","
                + "\"chunkedEntries\":[],\"transactionId\":\"12345\"}";

        final ScoringPayload payload = objectMapper.readValue(legacyJson, ScoringPayload.class);

        assertEquals("12345", payload.transactionId());
        assertNull(payload.clientId());
        assertNull(payload.originInvocationId());
    }

    @Test
    @DisplayName("a blob carrying a field this reader does not know is still readable (future additive fields)")
    void shouldIgnoreUnknownFields_fromNewerProducer() throws Exception {
        final String newerJson = "{\"userQuery\":\"q\",\"llmResponse\":\"a\",\"queryPrompt\":\"p\","
                + "\"chunkedEntries\":[],\"transactionId\":null,\"clientId\":null,"
                + "\"originInvocationId\":\"inv-1\",\"someFutureField\":\"x\"}";

        final ScoringPayload payload = objectMapper.readValue(newerJson, ScoringPayload.class);

        assertEquals("inv-1", payload.originInvocationId());
    }
}
