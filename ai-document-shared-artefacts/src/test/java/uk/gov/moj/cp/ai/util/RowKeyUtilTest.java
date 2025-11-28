package uk.gov.moj.cp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RowKeyUtilTest {

    @Test
    void generateKey_generatesConsistentKeyForRowAndPartitionForSameInput() {
        String input = "consistentInput";
        String key1 = RowKeyUtil.generateKeyForRowAndPartition(input);
        String key2 = RowKeyUtil.generateKeyForRowAndPartition(input);
        assertEquals(key1, key2);
    }

    @Test
    void generateKey_ForRowAndPartition_generatesDifferentKeysForDifferentInputs() {
        String input1 = "inputOne";
        String input2 = "inputTwo";
        String key1 = RowKeyUtil.generateKeyForRowAndPartition(input1);
        String key2 = RowKeyUtil.generateKeyForRowAndPartition(input2);
        assertNotEquals(key1, key2);
    }

    @Test
    void generateKey_trimsInputBeforeGeneratingKeyForRowAndPartition() {
        String input = "  trimmedInput  ";
        String trimmedKey = RowKeyUtil.generateKeyForRowAndPartition(input.trim());
        String key = RowKeyUtil.generateKeyForRowAndPartition(input);
        assertEquals(trimmedKey, key);
    }

    @Test
    void generateKey_ForRowAndPartition_throwsExceptionForNullInput() {
        assertThrows(NullPointerException.class, () -> RowKeyUtil.generateKeyForRowAndPartition(null));
    }
}
