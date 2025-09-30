package uk.gov.moj.cp.metadata.check.config;

import uk.gov.moj.cp.metadata.check.exception.PropertyValueNotFoundException;

/**
 * Simple static configuration class.
 */
public class Config {

    
    public static String getContainerName() {
        return getKeyValue("DOCUMENT_CONTAINER_NAME");
    }
    
    public static String getStorageAccountName() {
        return getKeyValue("STORAGE_ACCOUNT_NAME");
    }

    private static String getKeyValue(String key) {
        String envValue = System.getenv(key);
        if (envValue != null) {
            return envValue;
        }
        String propValue = System.getProperty(key);
        if (propValue != null) {
            return propValue;
        }
        throw new PropertyValueNotFoundException("Required configuration property '" + key + "' not found in environment variables or system properties");
    }
}
