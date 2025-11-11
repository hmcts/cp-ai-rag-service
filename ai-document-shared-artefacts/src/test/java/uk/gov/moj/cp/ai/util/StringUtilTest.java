package uk.gov.moj.cp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StringUtilTest {

    @Test
    void removeTrailingSlash_removesSlashWhenPresent() {
        String value = "example/";
        String result = StringUtil.removeTrailingSlash(value);
        assertEquals("example", result);
    }

    @Test
    void removeOnlyTrailingSlash() {
        String value = "http://example/";
        String result = StringUtil.removeTrailingSlash(value);
        assertEquals("http://example", result);
    }

    @Test
    void removeTrailingSlash_returnsSameStringWhenNoTrailingSlash() {
        String value = "example";
        String result = StringUtil.removeTrailingSlash(value);
        assertEquals("example", result);
    }

    @Test
    void removeTrailingSlash_returnsNullWhenInputIsNull() {
        String result = StringUtil.removeTrailingSlash(null);
        assertEquals(null, result);
    }

    @Test
    void removeTrailingSlash_returnsEmptyStringWhenInputIsEmpty() {
        String result = StringUtil.removeTrailingSlash("");
        assertEquals("", result);
    }

    @Test
    void removeTrailingSlash_handlesStringWithOnlySlash() {
        String value = "/";
        String result = StringUtil.removeTrailingSlash(value);
        assertEquals("", result);
    }
}
