package uk.gov.moj.cp.ai.client.identity;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specs for the worker-side {@link ClientId#requireValid(String)} helper. It rejects
 * null/blank/non-UUID values with the same {@link ClientIdentityException} used at the HTTP boundary,
 * and returns a valid UUID unchanged.
 */
class ClientIdTest {

    @Test
    @DisplayName("requireValid(null) → ClientIdentityException")
    void shouldThrow_whenNull() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValid(null));
    }

    @Test
    @DisplayName("requireValid(\"\") → ClientIdentityException")
    void shouldThrow_whenEmpty() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValid(""));
    }

    @Test
    @DisplayName("requireValid(blank) → ClientIdentityException")
    void shouldThrow_whenBlank() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValid("   "));
    }

    @Test
    @DisplayName("requireValid(non-UUID) → ClientIdentityException")
    void shouldThrow_whenNotUuid() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValid("not-a-uuid"));
    }

    @Test
    @DisplayName("requireValid(valid UUID) → returns the value unchanged")
    void shouldReturnValue_whenValidUuid() {
        final String clientId = randomUUID().toString();

        assertEquals(clientId, ClientId.requireValid(clientId));
    }

    @Test
    @DisplayName("requireValidOrNull(null) → null")
    void shouldReturnNull_whenNull() {
        assertNull(ClientId.requireValidOrNull(null));
    }

    @Test
    @DisplayName("requireValidOrNull(\"\") → null")
    void shouldReturnNull_whenEmpty() {
        assertNull(ClientId.requireValidOrNull(""));
    }

    @Test
    @DisplayName("requireValidOrNull(blank) → null")
    void shouldReturnNull_whenBlank() {
        assertNull(ClientId.requireValidOrNull("   "));
    }

    @Test
    @DisplayName("requireValidOrNull(valid UUID) → returns the value unchanged")
    void shouldReturnValue_whenValidUuidOrNull() {
        final String clientId = randomUUID().toString();

        assertEquals(clientId, ClientId.requireValidOrNull(clientId));
    }

    @Test
    @DisplayName("requireValidOrNull(non-UUID) → ClientIdentityException")
    void shouldThrow_whenPresentButNotUuid() {
        assertThrows(ClientIdentityException.class, () -> ClientId.requireValidOrNull("not-a-uuid"));
    }
}
