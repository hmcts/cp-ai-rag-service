package uk.gov.moj.cp.ai.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specs: the additive {@code IndexConstants.CLIENT_ID} constant exists and equals
 * {@code "clientId"}.
 */
class IndexConstantsTest {

    @Test
    @DisplayName("CLIENT_ID constant is defined as \"clientId\"")
    void shouldDefineClientIdConstant() {
        assertEquals("clientId", IndexConstants.CLIENT_ID);
    }
}
