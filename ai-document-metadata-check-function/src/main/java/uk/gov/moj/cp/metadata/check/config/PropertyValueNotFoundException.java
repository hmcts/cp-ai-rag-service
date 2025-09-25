package uk.gov.moj.cp.metadata.check.config;

/**
 * Exception thrown when a required configuration property is not found.
 */
public class PropertyValueNotFoundException extends RuntimeException {

    public PropertyValueNotFoundException(String message) {
        super(message);
    }
}
