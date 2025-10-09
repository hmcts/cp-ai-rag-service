package uk.gov.moj.cp.ingestion.model;

public class CustomMetadata {
    private String key;
    private String value;

    public CustomMetadata() {
        // Default constructor for serialization
    }

    public CustomMetadata(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "CustomMetadata{" +
               "key='" + key + '\'' +
               ", value='" + value + '\'' +
               '}';
    }
}