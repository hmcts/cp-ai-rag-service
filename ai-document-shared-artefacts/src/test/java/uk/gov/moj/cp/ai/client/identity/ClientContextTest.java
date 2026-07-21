package uk.gov.moj.cp.ai.client.identity;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MTDI-01 red-phase specs for {@link ClientContext} factory/accessor semantics. Expected to FAIL
 * until the skeleton factories/accessors are implemented (they currently throw).
 */
class ClientContextTest {

    @Test
    @DisplayName("unenforced() → enforced()==false and clientId() empty")
    void shouldBeUnenforcedWithEmptyClientId_whenUnenforced() {
        final ClientContext ctx = ClientContext.unenforced();

        assertFalse(ctx.enforced());
        assertTrue(ctx.clientId().isEmpty());
    }

    @Test
    @DisplayName("of(clientId) → enforced()==true and clientId() present with the given value")
    void shouldBeEnforcedWithClientId_whenOf() {
        final String clientId = randomUUID().toString();

        final ClientContext ctx = ClientContext.of(clientId);

        assertTrue(ctx.enforced());
        assertEquals(clientId, ctx.clientId().orElseThrow());
    }
}
