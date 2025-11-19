package uk.gov.moj.cp.ingestion.client;

import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.createNettyClient;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.getRetryOptions;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClient;
import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClientBuilder;
import com.azure.core.credential.TokenCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentAnalysisClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentAnalysisClientFactory.class);

    private static final ConcurrentHashMap<String, DocumentAnalysisClient> DOCUMENT_ANALYSIS_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private DocumentAnalysisClientFactory() {
    }

    public static DocumentAnalysisClient getInstance(final String endpoint) {

        validateNullOrEmpty(endpoint, "Endpoint value must be set.");

        return DOCUMENT_ANALYSIS_CLIENT_CACHE.computeIfAbsent(
                endpoint,
                key -> {
                    LOGGER.info("Creating new Document Analysis client for: {}", key);

                    return new DocumentAnalysisClientBuilder()
                            .endpoint(endpoint)
                            .credential(SHARED_CREDENTIAL)
                            .retryOptions(getRetryOptions())
                            .httpClient(createNettyClient())
                            .buildClient();
                }
        );

    }
}
