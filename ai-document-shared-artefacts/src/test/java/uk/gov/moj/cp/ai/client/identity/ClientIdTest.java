package uk.gov.moj.cp.ai.client.identity;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specs for the worker-side {@link ClientId#requireValid(String)} helper (MTDI-01, AC-007). It rejects
 * null/blank/non-UUID values with the same {@link ClientIdentityException} used at the HTTP boundary,
 * and returns a valid UUID unchanged.
 */
class ClientIdTest {

    @Test
    @DisplayName("AC-007: requireValid(null) → ClientIdentityException")
    void shouldThrow_whenNull() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValid(null));
    }

    @Test
    @DisplayName("AC-007: requireValid(\"\") → ClientIdentityException")
    void shouldThrow_whenEmpty() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValid(""));
    }

    @Test
    @DisplayName("AC-007: requireValid(blank) → ClientIdentityException")
    void shouldThrow_whenBlank() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValid("   "));
    }

    @Test
    @DisplayName("AC-007: requireValid(non-UUID) → ClientIdentityException")
    void shouldThrow_whenNotUuid() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValid("not-a-uuid"));
    }

    @Test
    @DisplayName("AC-007: requireValid(valid UUID) → returns the value unchanged")
    void shouldReturnValue_whenValidUuid() {
        final String clientId = randomUUID().toString();

        assertEquals(clientId, ClientId.requireValid(clientId));
    }
}
