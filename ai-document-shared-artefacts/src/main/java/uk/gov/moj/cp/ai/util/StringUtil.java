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
}
