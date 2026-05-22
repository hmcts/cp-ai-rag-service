package uk.gov.moj.cp.ai.util;

public class StringUtil {

    private StringUtil() {
    }

    public static void validateNullOrEmpty(final String value, final String errorMessage) {
        if (isNullOrEmpty(value)) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    public static boolean isNullOrEmpty(final String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String removeTrailingSlash(final String value) {
        if (isNullOrEmpty(value)) {
            return value;
        }
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    public static String unescapeContent(String content) {
        if (isNullOrEmpty(content)) {
            return content;
        }

        return content
                .replace("\\n", "\n")
                .replace("\\\"", "\"");
    }

    /**
     * Escapes all Lucene reserved characters in a string by preceding them with a backslash. This
     * is necessary when using QueryType.FULL and the user's input is intended as a literal search
     * phrase.
     * <p>
     * See <a href="https://learn.microsoft.com/en-gb/azure/search/query-lucene-syntax">info</a>
     *
     * @param userQuery The raw query string provided by the user.
     * @return The escaped query string.
     */
    /**
     * Escapes embedded single quotes in a value destined for an OData string literal by doubling
     * them, per the OData v4 grammar rule {@code string_literal ::= "'"([^'] | "''")*"'"}. Must be
     * applied to any caller-supplied value before interpolation into an OData {@code $filter}
     * expression, otherwise apostrophes break the parse and unescaped input enables filter-bypass
     * injection (including bypass of security-trimming filters).
     * <p>
     * See <a href="https://learn.microsoft.com/en-us/azure/search/query-odata-filter-orderby-syntax">info</a>
     *
     * @param value The raw value to be embedded between single quotes in an OData string literal.
     * @return The escaped value with all single quotes doubled.
     */
    public static String escapeODataStringLiteral(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("'", "''");
    }

    public static String escapeLuceneSpecialChars(final String userQuery) {
        if (userQuery == null || userQuery.isEmpty()) {
            return "";
        }

        String[] reservedChars = new String[]{
                "\"", "`", "<", ">", "#", "%", "(", ")", "{", "}", "|", "\\", "^", "~", "[", "]", ";", "/", "?", ":", "@", "=", "+", "-", "*", "&"
        };

        String escapedQuery = userQuery;

        // 1. Escape the backslash itself first to prevent double-escaping later
        escapedQuery = escapedQuery.replace("\\", "\\\\");

        // 2. Escape all other reserved single-character and multi-character operators
        for (String reserved : reservedChars) {
            // Skip the backslash since it was handled above
            if (reserved.equals("\\")) {
                continue;
            }

            // Escape the character/string and replace it
            // The replacement string needs two backslashes: one for Java string literal, one for Lucene.
            escapedQuery = escapedQuery.replace(reserved, "\\" + reserved);
        }

        return escapedQuery;
    }
}
