package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.client.ConnectionMode.CONNECTION_STRING;
import static uk.gov.moj.cp.ai.client.ConnectionMode.MANAGED_IDENTITY;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.createNettyClient;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.getRetryOptions;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.FunctionEnvironment;

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

    public static TableClient getInstance(final String tableName) {

        validateNullOrEmpty(tableName, "Table name variable must be set.");
        final FunctionEnvironment env = FunctionEnvironment.get();

        final ConnectionMode connectionMode = ConnectionMode.valueOf(env.storageConfig().accountConnectionMode());

        return switch (connectionMode) {
            case CONNECTION_STRING -> {
                final String connectionString = env.storageConfig().accountName();
                final String cacheKey = connectionString + ":" + tableName;

                yield TABLE_CLIENT_CACHE.computeIfAbsent(cacheKey, key -> {
                    LOGGER.info("Creating new Table client using {} for  {}", CONNECTION_STRING, key);
                    return new TableClientBuilder()
                            .connectionString(connectionString)
                            .tableName(tableName)
                            .retryOptions(getRetryOptions())
                            .httpClient(createNettyClient())
                            .buildClient();
                });
            }
            case MANAGED_IDENTITY -> {

                final String endpoint = env.storageConfig().tableEndpoint();
                final String cacheKey = endpoint + ":" + tableName;

                yield TABLE_CLIENT_CACHE.computeIfAbsent(
                        cacheKey,
                        key -> {
                            LOGGER.info("Creating new Table client using {} for  {}", MANAGED_IDENTITY, key);
                            return new TableClientBuilder()
                                    .endpoint(endpoint)
                                    .tableName(tableName)
                                    .credential(SHARED_CREDENTIAL)
                                    .retryOptions(getRetryOptions())
                                    .httpClient(createNettyClient())
                                    .buildClient();
                        }
                );
            }
        };
    }
}
