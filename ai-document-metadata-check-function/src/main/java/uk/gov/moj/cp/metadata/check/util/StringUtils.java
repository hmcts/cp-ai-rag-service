package uk.gov.moj.cp.metadata.check.util;


public class StringUtils {

    public static boolean isNullOrBlank(String input) {
        return input == null || input.trim().isEmpty();
    }
}

