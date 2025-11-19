package uk.gov.moj.cp.ai.util;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

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
            validateNullOrEmpty(defaultValue, "Required environment variable not set: " + key);
            return defaultValue;
        }
        return value;
    }

    public static int getRequiredEnvAsInteger(final String key, final String defaultValue) {
        try {
            String value = getRequiredEnv(key, defaultValue);
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Required environment variable or supplied default value does not parse as integer value: " + key);
        }
    }
}
