package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.SharedSystemVariables.EMBEDDING_SERVICE_PROVIDER;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.service.AzureEmbeddingService;
import uk.gov.moj.cp.ai.service.EmbeddingService;
import uk.gov.moj.cp.ai.service.OpenAiEmbeddingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddingServiceFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingServiceFactory.class);

    private static final String PROVIDER_AZURE = "azure";
    private static final String PROVIDER_OPENAI = "openai";

    private EmbeddingServiceFactory() {
    }

    public static EmbeddingService getInstance(final String endpoint, final String deploymentName) {
        return getInstance(endpoint, deploymentName, getRequiredEnv(EMBEDDING_SERVICE_PROVIDER, PROVIDER_OPENAI));
    }

    // Package-private overload used by tests to bypass the System.getenv read.
    static EmbeddingService getInstance(final String endpoint, final String deploymentName, final String provider) {
        if (provider == null || provider.isBlank()) {
            LOGGER.info("EMBEDDING_SERVICE_PROVIDER not set; defaulting to OpenAiEmbeddingService for deployment '{}'", deploymentName);
            return new OpenAiEmbeddingService(endpoint, deploymentName);
        }
        final String normalised = provider.trim().toLowerCase();
        return switch (normalised) {
            case PROVIDER_AZURE -> {
                LOGGER.info("Creating AzureEmbeddingService for deployment '{}'", deploymentName);
                yield new AzureEmbeddingService(endpoint, deploymentName);
            }
            case PROVIDER_OPENAI -> {
                LOGGER.info("Creating OpenAiEmbeddingService for deployment '{}'", deploymentName);
                yield new OpenAiEmbeddingService(endpoint, deploymentName);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown EMBEDDING_SERVICE_PROVIDER value: '" + provider + "'. Expected one of: azure, openai.");
        };
    }
}
