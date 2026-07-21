package uk.gov.moj.cp.ai.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specs: the additive {@code StorageTableColumns.TC_CLIENT_ID} column constant exists.
 * Unused by any table read/write path yet.
 */
class StorageTableColumnsTest {

    @Test
    @DisplayName("TC_CLIENT_ID column constant is defined")
    void shouldDefineClientIdColumnConstant() {
        assertEquals("ClientId", StorageTableColumns.TC_CLIENT_ID);
    }
}
