package uk.gov.moj.cp.ai.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MTDI-02 (AC-008) spec: the additive {@code IndexConstants.CLIENT_ID} constant exists and equals
 * {@code "clientId"}. Pure-data AC — passes on the additive skeleton.
 */
class IndexConstantsTest {

    @Test
    @DisplayName("AC-008: CLIENT_ID constant is defined as \"clientId\"")
    void shouldDefineClientIdConstant() {
        assertEquals("clientId", IndexConstants.CLIENT_ID);
    }
}
