package uk.gov.moj.cp.ai.util;

/**
 * Utility class for string operations.
 */
public class StringUtils {

    private StringUtils() {
    }

    /**
     * Checks if a string is null or blank (empty or whitespace only).
     */
    public static boolean isNullOrBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

}