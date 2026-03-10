package uk.gov.moj.cp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.gov.moj.cp.ai.util.StringUtil.escapeLuceneSpecialChars;
import static uk.gov.moj.cp.ai.util.StringUtil.removeTrailingSlash;
import static uk.gov.moj.cp.ai.util.StringUtil.unescapeContent;

import org.junit.jupiter.api.Test;

class StringUtilTest {

    @Test
    void removeTrailingSlash_removesSlashWhenPresent() {
        final String value = "example/";
        final String result = removeTrailingSlash(value);
        assertEquals("example", result);
    }

    @Test
    void removeOnlyTrailingSlash() {
        final String value = "http://example/";
        final String result = removeTrailingSlash(value);
        assertEquals("http://example", result);
    }

    @Test
    void removeTrailingSlash_returnsSameStringWhenNoTrailingSlash() {
        final String value = "example";
        final String result = removeTrailingSlash(value);
        assertEquals("example", result);
    }

    @Test
    void removeTrailingSlash_returnsNullWhenInputIsNull() {
        final String result = removeTrailingSlash(null);
        assertEquals(null, result);
    }

    @Test
    void removeTrailingSlash_returnsEmptyStringWhenInputIsEmpty() {
        final String result = removeTrailingSlash("");
        assertEquals("", result);
    }

    @Test
    void removeTrailingSlash_handlesStringWithOnlySlash() {
        final String value = "/";
        final String result = removeTrailingSlash(value);
        assertEquals("", result);
    }

    @Test
    void escapeLuceneSpecialChars_returnsEmptyStringWhenInputIsNull() {
        final String result = escapeLuceneSpecialChars(null);
        assertEquals("", result);
    }

    @Test
    void escapeLuceneSpecialChars_returnsEmptyStringWhenInputIsEmpty() {
        final String result = escapeLuceneSpecialChars("");
        assertEquals("", result);
    }

    @Test
    void escapeLuceneSpecialChars_escapesAllReservedCharacters() {
        final String input = "\"`<>#%(){}|\\^~[];/? :@=+-*&";
        final String expected = "\\\"\\`\\<\\>\\#\\%\\(\\)\\{\\}\\|\\\\\\^\\~\\[\\]\\;\\/\\? \\:\\@\\=\\+\\-\\*\\&";
        final String result = escapeLuceneSpecialChars(input);
        assertEquals(expected, result);
    }

    @Test
    void escapeLuceneSpecialChars_escapesBackslashOnlyOnce() {
        final String input = "\\";
        final String expected = "\\\\";
        final String result = escapeLuceneSpecialChars(input);
        assertEquals(expected, result);
    }

    @Test
    void escapeLuceneSpecialChars_leavesUnreservedCharactersUnchanged() {
        final String input = "abc123";
        final String result = escapeLuceneSpecialChars(input);
        assertEquals("abc123", result);
    }

    @Test
    void escapeLuceneSpecialChars_escapesMixedContent() {
        final String input = "abc/def:ghi";
        final String expected = "abc\\/def\\:ghi";
        final String result = escapeLuceneSpecialChars(input);
        assertEquals(expected, result);
    }

    @Test
    void unescapeContent_returnsNullWhenInputIsNull() {
        assertEquals(null, unescapeContent(null));
    }

    @Test
    void unescapeContent_returnsEmptyStringWhenInputIsEmpty() {
        assertEquals("", unescapeContent(""));
    }

    @Test
    void unescapeContent_replacesEscapedNewlineAndQuote() {
        String input = "Line1\\nLine2\\\"Quoted\\\"";
        String expected = "Line1\nLine2\"Quoted\"";
        assertEquals(expected, unescapeContent(input));
    }

    @Test
    void unescapeContent_doesNotChangeStringWithoutEscapedCharacters() {
        String input = "Just a normal string";
        assertEquals(input, unescapeContent(input));
    }
}
