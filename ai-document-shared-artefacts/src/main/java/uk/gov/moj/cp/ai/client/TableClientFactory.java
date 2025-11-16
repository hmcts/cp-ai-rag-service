package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.core.credential.TokenCredential;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableClientFactory.class);

    private static final ConcurrentHashMap<String, TableClient> TABLE_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private TableClientFactory() {
    }

    public static TableClient getInstance(final String endpoint, final String tableName) {

        validateNullOrEmpty(endpoint, "Endpoint variable must be set.");
        validateNullOrEmpty(tableName, "Table name variable must be set.");

        final String cacheKey = endpoint + ":" + tableName;

        return TABLE_CLIENT_CACHE.computeIfAbsent(
                cacheKey,
                key -> {
                    LOGGER.info("Creating new Table client for: {}", key);

                    // CRITICAL: The client is built here using the single, shared credential.
                    return new TableClientBuilder()
                            .endpoint(endpoint)
                            .tableName(tableName)
                            .credential(SHARED_CREDENTIAL)
                            .buildClient();
                }
        );

    }
}
