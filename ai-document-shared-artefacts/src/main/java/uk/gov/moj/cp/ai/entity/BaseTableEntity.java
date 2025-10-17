package uk.gov.moj.cp.ai.model;

import static uk.gov.moj.cp.ai.util.RowKeyUtil.generateRowKey;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

public abstract class BaseTableEntity {

    private String partitionKey;
    private String rowKey;

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    public String getRowKey() {
        return rowKey;
    }

    public void setRowKey(String rowKey) {
        this.rowKey = rowKey;
    }

    /**
     * Automatically generates a partition key for current date (YYYYMMDD format).
     */
    public void generateDefaultPartitionKey(String documentId) {
        this.partitionKey = isNullOrEmpty(documentId)
                ? "UNKNOWN_DOCUMENT"
                : documentId.trim();
    }

    /**
     * Generates a deterministic RowKey from a name
     */
    public void generateRowKeyFrom(String name) {
        this.rowKey = generateRowKey(name);
    }
}
