package uk.gov.moj.cp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RowKeyUtilTest {

    @Test
    void generateRowKey_generatesConsistentKeyForSameInput() {
        String input = "consistentInput";
        String key1 = RowKeyUtil.generateRowKey(input);
        String key2 = RowKeyUtil.generateRowKey(input);
        assertEquals(key1, key2);
    }

    @Test
    void generateRowKey_generatesDifferentKeysForDifferentInputs() {
        String input1 = "inputOne";
        String input2 = "inputTwo";
        String key1 = RowKeyUtil.generateRowKey(input1);
        String key2 = RowKeyUtil.generateRowKey(input2);
        assertNotEquals(key1, key2);
    }

    @Test
    void generateRowKey_trimsInputBeforeGeneratingKey() {
        String input = "  trimmedInput  ";
        String trimmedKey = RowKeyUtil.generateRowKey(input.trim());
        String key = RowKeyUtil.generateRowKey(input);
        assertEquals(trimmedKey, key);
    }

    @Test
    void generateRowKey_throwsExceptionForNullInput() {
        assertThrows(NullPointerException.class, () -> RowKeyUtil.generateRowKey(null));
    }
}
