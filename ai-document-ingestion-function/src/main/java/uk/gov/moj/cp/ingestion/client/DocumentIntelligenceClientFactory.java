package uk.gov.moj.cp.ingestion.client;

import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.createNettyClient;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.getRetryOptions;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.DocumentIntelligenceClientBuilder;
import com.azure.core.credential.TokenCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentIntelligenceClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIntelligenceClientFactory.class);

    private static final ConcurrentHashMap<String, DocumentIntelligenceClient> DOCUMENT_INTELLIGENCE_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private DocumentIntelligenceClientFactory() {
    }

    public static DocumentIntelligenceClient getInstance(final String endpoint) {

        validateNullOrEmpty(endpoint, "Endpoint value must be set.");

        return DOCUMENT_INTELLIGENCE_CLIENT_CACHE.computeIfAbsent(
                endpoint,
                key -> {
                    LOGGER.info("Creating new Document Analysis client for: {}", key);

                    return new DocumentIntelligenceClientBuilder()
                            .endpoint(endpoint)
                            .credential(SHARED_CREDENTIAL)
                            .retryOptions(getRetryOptions())
                            .httpClient(createNettyClient())
                            .buildClient();
                }
        );

    }
}
