package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_CHAT_SERVICE_PROVIDER;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.service.AnthropicChatService;
import uk.gov.moj.cp.ai.service.AzureChatService;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.service.OpenAiChatService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatServiceFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatServiceFactory.class);

    private static final String PROVIDER_AZURE = "azure";
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_CLAUDE_GATEWAY = "claude-gateway";

    private ChatServiceFactory() {
    }

    public static ChatService getInstance(final String endpoint, final String deploymentName) {
        return getInstance(endpoint, deploymentName, getRequiredEnv(LLM_CHAT_SERVICE_PROVIDER, PROVIDER_OPENAI));
    }

    /**
     * Builds the chat service for a pipeline role from its settings: the role's provider switch picks
     * which provider's endpoint and model settings are read (see {@link ChatRole}).
     */
    public static ChatService getInstance(final ChatRole role) {
        final ChatRole.Target target = role.resolve();
        return getInstance(target.endpoint(), target.model(), target.provider());
    }

    /**
     * Builds the chat service for an explicit provider value ({@code azure} | {@code openai} |
     * {@code claude-gateway}; blank means {@code openai}), for callers that choose their own
     * provider setting, such as the scoring judge.
     */
    public static ChatService getInstance(final String endpoint, final String deploymentName, final String provider) {
        if (provider == null || provider.isBlank()) {
            LOGGER.info("LLM_CHAT_SERVICE_PROVIDER not set; defaulting to OpenAiChatService for deployment '{}'", deploymentName);
            return new OpenAiChatService(endpoint, deploymentName);
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
            case PROVIDER_CLAUDE_GATEWAY -> {
                LOGGER.info("Creating AnthropicChatService (AI gateway) for model '{}'", deploymentName);
                yield new AnthropicChatService(endpoint, deploymentName);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown chat service provider value: '" + provider + "'. Expected one of: azure, openai, claude-gateway.");
        };
    }
}
