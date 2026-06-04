package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_CHAT_SERVICE_PROVIDER;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.service.AzureChatService;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.service.OpenAiChatService;
import uk.gov.moj.cp.ai.util.EnvVarUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatServiceFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatServiceFactory.class);

    private static final String PROVIDER_AZURE = "azure";
    private static final String PROVIDER_OPENAI = "openai";

    private ChatServiceFactory() {
    }

    public static ChatService getInstance(final String endpoint, final String deploymentName) {
        return getInstance(endpoint, deploymentName, getRequiredEnv(LLM_CHAT_SERVICE_PROVIDER, PROVIDER_AZURE));
    }

    // Package-private overload used by tests to bypass the System.getenv read.
    static ChatService getInstance(final String endpoint, final String deploymentName, final String provider) {
        if (provider == null || provider.isBlank()) {
            LOGGER.info("LLM_CHAT_SERVICE_PROVIDER not set; defaulting to AzureChatService for deployment '{}'", deploymentName);
            return new AzureChatService(endpoint, deploymentName);
        }
        final String normalised = provider.trim().toLowerCase();
        return switch (normalised) {
            case PROVIDER_AZURE -> {
                LOGGER.info("Creating AzureChatService for deployment '{}'", deploymentName);
                yield new AzureChatService(endpoint, deploymentName);
            }
            case PROVIDER_OPENAI -> {
                LOGGER.info("Creating OpenAiChatService for deployment '{}'", deploymentName);
                yield new OpenAiChatService(endpoint, deploymentName);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown LLM_CHAT_SERVICE_PROVIDER value: '" + provider + "'. Expected one of: azure, openai.");
        };
    }
}
