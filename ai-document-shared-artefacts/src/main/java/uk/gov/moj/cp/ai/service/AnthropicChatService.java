package uk.gov.moj.cp.ai.service;

import static java.util.stream.Collectors.joining;
import static uk.gov.moj.cp.ai.util.ChatModelUtil.ensureRawJsonAsConvertingPayloadToObject;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.AiGatewayBackend;
import uk.gov.moj.cp.ai.client.AnthropicClientFactory;
import uk.gov.moj.cp.ai.exception.ChatServiceException;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChatService} for Anthropic Claude models through the Anthropic Messages API.
 *
 * <p>The public constructor reaches Claude through the AI gateway (see
 * {@link AnthropicClientFactory#getAiGatewayInstance}); the other constructor takes any
 * {@link AnthropicClient}, so other hosts (for example Azure AI Foundry or Amazon Bedrock) can
 * reuse the same request and response handling.
 *
 * <p>Responses are returned as the raw text for {@code String}, or parsed from JSON for any other
 * class, as {@link OpenAiChatService} does.
 */
public class AnthropicChatService implements ChatService, TokenUsageReporting {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicChatService.class);

    private final AnthropicClient client;
    private final String model;
    private final AnthropicModelSettings settings;
    private final String hostLabel;

    /** Optional per-call usage listener (see {@link TokenUsageReporting}); no-op when unset. */
    private volatile Consumer<TokenUsage> tokenUsageListener;

    /**
     * Claude through the AI gateway, with request settings from the environment
     * ({@link AnthropicModelSettings#fromEnvironment()}).
     *
     * @param gatewayBaseUrl the gateway's Messages API base URL
     * @param model          the model ID the gateway allows, e.g. {@code eu.anthropic.claude-sonnet-4-6}
     */
    public AnthropicChatService(final String gatewayBaseUrl, final String model) {
        this(AnthropicClientFactory.getAiGatewayInstance(gatewayBaseUrl), model,
                AnthropicModelSettings.fromEnvironment(), "AI gateway");
    }

    /**
     * @param client    a configured Anthropic client (it owns routing and authentication)
     * @param model     the value sent as the Messages API {@code model}
     * @param settings  request settings
     * @param hostLabel where the model is hosted, used in logs and error messages
     */
    public AnthropicChatService(final AnthropicClient client, final String model, final AnthropicModelSettings settings,
                                final String hostLabel) {
        validateNullOrEmpty(model, "Anthropic model name must be set.");
        this.client = Objects.requireNonNull(client, "client");
        this.model = model;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.hostLabel = hostLabel == null || hostLabel.isBlank() ? "Anthropic" : hostLabel;
    }

    @Override
    public <T> Optional<T> callModel(final String systemInstruction, final String userInstruction,
                                     final Class<T> responseClass) throws ChatServiceException {
        try {
            final Message message = client.messages().create(buildParams(systemInstruction, userInstruction));
            reportTokenUsage(message.usage());
            logStopReason(message);

            final String text = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(joining());
            if (text.isBlank()) {
                throw new ChatServiceException("LLM produced an empty response. Stop reason: "
                        + message.stopReason().map(StopReason::toString).orElse("(none)"));
            }

            if (responseClass == String.class) {
                return Optional.of(responseClass.cast(text));
            }
            return Optional.ofNullable(getObjectMapper().readValue(ensureRawJsonAsConvertingPayloadToObject(text), responseClass));
        } catch (final AnthropicServiceException e) {
            final String correlation = e.headers().values(AiGatewayBackend.HEADER_CORRELATION_ID).stream().findFirst()
                    .map(id -> " (x-correlation-id: " + id + ")").orElse("");
            throw new ChatServiceException(hostLabel + " call failed with HTTP " + e.statusCode() + " for model '" + model
                    + "'" + correlation, e);
        } catch (final AnthropicException e) {
            throw new ChatServiceException(hostLabel + " call failed for model '" + model + "'", e);
        } catch (final JsonProcessingException e) {
            throw new ChatServiceException("Could not parse the response of model '" + model + "' as "
                    + responseClass.getSimpleName(), e);
        }
    }

    MessageCreateParams buildParams(final String systemInstruction, final String userInstruction) {
        final MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(settings.maxTokens())
                .system(systemInstruction)
                .addUserMessage(userInstruction);

        settings.temperature().ifPresent(params::temperature);
        switch (settings.thinking()) {
            case ADAPTIVE -> params.thinking(ThinkingConfigAdaptive.builder().build());
            case DISABLED -> params.thinking(ThinkingConfigDisabled.builder().build());
            case MODEL_DEFAULT -> {
                // Send nothing: the model's own default applies.
            }
        }
        settings.effort().ifPresent(effort ->
                params.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build()));
        return params.build();
    }

    @Override
    public void setTokenUsageListener(final Consumer<TokenUsage> listener) {
        this.tokenUsageListener = listener;
    }

    private void reportTokenUsage(final Usage usage) {
        final TokenUsage tokenUsage = new TokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L),
                usage.cacheReadInputTokens().orElse(0L));
        LOGGER.info("Token usage for model '{}' ({}): input={} output={} (thinking={}) cachedInput={}",
                model, hostLabel, tokenUsage.inputTokens(), tokenUsage.outputTokens(),
                tokenUsage.reasoningTokens(), tokenUsage.cachedInputTokens());
        final Consumer<TokenUsage> listener = tokenUsageListener;
        if (listener != null) {
            listener.accept(tokenUsage);
        }
    }

    private void logStopReason(final Message message) {
        final StopReason stopReason = message.stopReason().orElse(null);
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            LOGGER.warn("Response from model '{}' truncated at max_tokens={}; the answer may be incomplete",
                    model, settings.maxTokens());
        } else if (StopReason.REFUSAL.equals(stopReason)) {
            LOGGER.warn("Model '{}' returned stop_reason=refusal; content may be empty or partial", model);
        } else {
            LOGGER.info("Received response from model '{}'. Stop reason: {}", model, stopReason);
        }
    }
}
