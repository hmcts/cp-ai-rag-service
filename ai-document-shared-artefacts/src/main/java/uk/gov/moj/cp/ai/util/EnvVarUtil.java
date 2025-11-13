package uk.gov.moj.cp.ai.util;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.exception.EnvVarNotFoundException;

public class EnvVarUtil {

    private EnvVarUtil() {
        // Utility class
    }

    public static String getRequiredEnv(final String key) {
        return getRequiredEnv(key, null);
    }

    public static String getRequiredEnv(final String key, final String defaultValue) {
        String value = System.getenv(key);
        if (isNullOrEmpty(value)) {
            if (isNullOrEmpty(defaultValue)) {
                throw new EnvVarNotFoundException("Required environment variable not set: " + key);
            }

            return defaultValue;
        }
        return value;
    }
}
