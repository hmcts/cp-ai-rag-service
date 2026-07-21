package uk.gov.moj.cp.ai.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MTDI-02 (AC-013) spec: the additive {@code StorageTableColumns.TC_CLIENT_ID} column constant exists.
 * Pure-data AC — passes on the additive skeleton (unused by any table read/write path yet).
 */
class StorageTableColumnsTest {

    @Test
    @DisplayName("AC-013: TC_CLIENT_ID column constant is defined")
    void shouldDefineClientIdColumnConstant() {
        assertEquals("ClientId", StorageTableColumns.TC_CLIENT_ID);
    }
}
