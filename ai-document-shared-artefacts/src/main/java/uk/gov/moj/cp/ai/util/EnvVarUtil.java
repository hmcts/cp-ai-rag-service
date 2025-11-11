package uk.gov.moj.cp.ai.util;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

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
                throw new IllegalStateException("Required environment variable not set: " + key);
            }

            return defaultValue;
        }
        return value;
    }
}
