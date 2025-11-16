package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.data.tables.TableClient;
import org.junit.jupiter.api.Test;

class TableClientFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String TABLE_NAME = "example-table";
    private static final String DIFFERENT_ENDPOINT = "https://different-endpoint.com";
    private static final String DIFFERENT_TABLE_NAME = "different-table";

    @Test
    void getInstanceCreatesNewTableClientWhenNotInCache() {
        TableClient client = TableClientFactory.getInstance(ENDPOINT, TABLE_NAME);
        assertNotNull(client);
    }

    @Test
    void getInstanceReturnsCachedTableClientForSameEndpointAndTableName() {
        TableClient firstClient = TableClientFactory.getInstance(ENDPOINT, TABLE_NAME);
        TableClient secondClient = TableClientFactory.getInstance(ENDPOINT, TABLE_NAME);
        assertSame(firstClient, secondClient);
    }

    @Test
    void getInstanceReturnsNewTableClientForDifferentEndpointOrTableName() {
        TableClient firstClient = TableClientFactory.getInstance(ENDPOINT, TABLE_NAME);
        TableClient secondClient = TableClientFactory.getInstance(ENDPOINT, DIFFERENT_TABLE_NAME);
        TableClient thirdClient = TableClientFactory.getInstance(DIFFERENT_ENDPOINT, TABLE_NAME);
        assertNotSame(firstClient, secondClient);
        assertNotSame(firstClient, thirdClient);
        assertNotSame(secondClient, thirdClient);
    }

    @Test
    void getInstanceThrowsExceptionForNullEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> TableClientFactory.getInstance(null, TABLE_NAME));
    }

    @Test
    void getInstanceThrowsExceptionForEmptyEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> TableClientFactory.getInstance("", TABLE_NAME));
    }

    @Test
    void getInstanceThrowsExceptionForNullTableName() {
        assertThrows(IllegalArgumentException.class, () -> TableClientFactory.getInstance(ENDPOINT, null));
    }

    @Test
    void getInstanceThrowsExceptionForEmptyTableName() {
        assertThrows(IllegalArgumentException.class, () -> TableClientFactory.getInstance(ENDPOINT, ""));
    }
}
