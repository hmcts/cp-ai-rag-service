package uk.gov.moj.cp.metadata.check.util;

/**
 * Utility class for string operations.
 */
public class StringUtils {
    
    /**
     * Checks if a string is null or blank (empty or whitespace only).
     */
    public static boolean isNullOrBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}