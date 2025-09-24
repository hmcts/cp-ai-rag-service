package uk.gov.moj.cp.metadata.check.config;

/**
 * Simple static configuration class.
 */
public class Config {
    
    public static String getStorageConnectionString() {
        String envValue = System.getenv("AzureWebJobsStorage");
        if (envValue != null) {
            return envValue;
        }
        return System.getProperty("AzureWebJobsStorage");
    }
    
    public static String getQueueName() {
        String envValue = System.getenv("DOCUMENT_PROCESSING_QUEUE_NAME");
        if (envValue != null) {
            return envValue;
        }
        String propValue = System.getProperty("DOCUMENT_PROCESSING_QUEUE_NAME");
        return propValue != null ? propValue : "document-processing-queue";
    }
    
    public static String getContainerName() {
        String envValue = System.getenv("DOCUMENT_CONTAINER_NAME");
        if (envValue != null) {
            return envValue;
        }
        String propValue = System.getProperty("DOCUMENT_CONTAINER_NAME");
        return propValue != null ? propValue : "documents";
    }
    
    public static String getStorageAccountName() {
        String envValue = System.getenv("STORAGE_ACCOUNT_NAME");
        if (envValue != null) {
            return envValue;
        }
        String propValue = System.getProperty("STORAGE_ACCOUNT_NAME");
        return propValue != null ? propValue : "defaultstorageaccount";
    }
}
