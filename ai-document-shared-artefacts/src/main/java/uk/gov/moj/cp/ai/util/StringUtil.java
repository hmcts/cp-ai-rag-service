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
