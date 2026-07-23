package uk.gov.moj.cp.ai.util;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary-validation variant reports UUID validity without treating a malformed value as a
 * server-side error. A caller supplying a bad identity at the edge is an expected, client-driven
 * rejection, so this path exists precisely so that the edge check does not log at ERROR level for
 * a mere invalid header.
 */
class UuidUtilQuietValidityTest {

    @Test
    @DisplayName("reports true for a well-formed UUID")
    void shouldReportValid_forWellFormedUuid() {
        assertTrue(UuidUtil.isValidQuietly(randomUUID().toString()));
    }

    @Test
    @DisplayName("reports false for a malformed value without treating it as a server error")
    void shouldReportInvalid_forMalformedValue() {
        assertFalse(UuidUtil.isValidQuietly("not-a-uuid"));
    }

    @Test
    @DisplayName("reports false for null and blank input")
    void shouldReportInvalid_forNullAndBlank() {
        assertFalse(UuidUtil.isValidQuietly(null));
        assertFalse(UuidUtil.isValidQuietly(""));
        assertFalse(UuidUtil.isValidQuietly("   "));
    }
}
