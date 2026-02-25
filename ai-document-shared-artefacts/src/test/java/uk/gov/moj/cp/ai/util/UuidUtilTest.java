package uk.gov.moj.cp.ai.util;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.ai.util.UuidUtil.isValid;

import org.junit.jupiter.api.Test;

public class UuidUtilTest {

    @Test
    void shouldReturnTrue_whenValidUuid() {
        String validUuid = randomUUID().toString();

        boolean result = isValid(validUuid);

        assertTrue(result);
    }

    @Test
    void shouldReturnFalse_whenInvalidUuidFormat() {
        String invalidUuid = "not-a-uuid";

        boolean result = isValid(invalidUuid);

        assertFalse(result);
    }

    @Test
    void shouldThrowException_whenNullPassed() {
        assertThrows(NullPointerException.class, () -> UuidUtil.isValid(null));
    }

    @Test
    void shouldReturnFalse_whenEmptyString() {
        boolean result = isValid("");

        assertFalse(result);
    }

    @Test
    void shouldReturnFalse_whenUuidHasWrongLength() {
        String wrongLengthUuid = "1234";

        boolean result = isValid(wrongLengthUuid);

        assertFalse(result);
    }
}