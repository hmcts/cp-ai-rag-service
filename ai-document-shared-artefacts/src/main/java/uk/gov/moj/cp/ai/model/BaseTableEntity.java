package uk.gov.moj.cp.ai.model;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
    public void generateDefaultPartitionKey() {
        this.partitionKey = LocalDate.now().toString().replace("-", "");
    }

    /**
     * Generates a deterministic RowKey from a name or documentId.
     */
    public void generateRowKeyFrom(String name) {
        if (isNullOrEmpty(name)) {
            this.rowKey = UUID.randomUUID().toString();
            return;
        }
        byte[] bytes = name.trim().getBytes(StandardCharsets.UTF_8);
        this.rowKey = UUID.nameUUIDFromBytes(bytes).toString();
    }
}
