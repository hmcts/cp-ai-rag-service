package uk.gov.moj.cp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EnvVarUtilTest {

    @Test
    void getRequiredEnv_returnsDefaultValueWhenEnvVarIsNotSet() {
        String key = "NON_EXISTING_ENV_VAR";
        String defaultValue = "default";

        String result = EnvVarUtil.getRequiredEnv(key, defaultValue);

        assertEquals(defaultValue, result);
    }

    @Test
    void getRequiredEnv_throwsExceptionWhenEnvVarIsNotSetAndNoDefaultValueProvided() {
        String key = "NON_EXISTING_ENV_VAR";

        assertThrows(IllegalStateException.class, () -> EnvVarUtil.getRequiredEnv(key));
    }

    @Test
    void getRequiredEnv_throwsExceptionWhenKeyIsNull() {
        assertThrows(NullPointerException.class, () -> EnvVarUtil.getRequiredEnv(null));
    }
}
