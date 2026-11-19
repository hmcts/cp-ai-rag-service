package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_ANTHROPIC_EFFORT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_ANTHROPIC_TEMPERATURE;
import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_ANTHROPIC_THINKING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_MODEL_RESPONSE_MAX_TOKENS;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import java.util.Locale;
import java.util.Optional;

/**
 * Request settings for {@link AnthropicChatService}.
 *
 * @param maxTokens   the mandatory Anthropic {@code max_tokens}; it must cover the whole answer
 *                    (and any thinking, which shares the budget)
 * @param temperature optional single sampling parameter; Claude rejects {@code temperature} and
 *                    {@code top_p} together, so {@code top_p} is never sent
 * @param thinking    whether to send a thinking setting; {@link Thinking#MODEL_DEFAULT} omits it
 * @param effort      optional {@code output_config.effort} ({@code low|medium|high|xhigh|max})
 */
public record AnthropicModelSettings(long maxTokens, Optional<Double> temperature, Thinking thinking,
                                     Optional<String> effort) {

    static final String DEFAULT_MAX_TOKENS = "1000";

    /** Thinking setting sent with each request. */
    public enum Thinking {
        /** Send nothing: the model's own default (off for Sonnet 4.6, on for newer models). */
        MODEL_DEFAULT,
        /** Adaptive thinking; thinking tokens share the {@code max_tokens} budget. */
        ADAPTIVE,
        /** Explicitly off, for models that think by default when the field is omitted. */
        DISABLED;

        static Thinking parse(final String value) {
            if (isNullOrEmpty(value)) {
                return MODEL_DEFAULT;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "adaptive" -> ADAPTIVE;
                case "disabled" -> DISABLED;
                default -> throw new IllegalArgumentException("Unknown " + LLM_ANTHROPIC_THINKING + " value: '" + value
                        + "'. Expected one of: adaptive, disabled (or unset for the model's default).");
            };
        }
    }

    public AnthropicModelSettings {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive, was " + maxTokens);
        }
        temperature = temperature == null ? Optional.empty() : temperature;
        thinking = thinking == null ? Thinking.MODEL_DEFAULT : thinking;
        effort = effort == null ? Optional.empty() : effort.map(e -> e.trim().toLowerCase(Locale.ROOT)).filter(e -> !e.isEmpty());
    }

    /** Settings with only {@code max_tokens} set; everything else at the model's default. */
    public static AnthropicModelSettings withMaxTokens(final long maxTokens) {
        return new AnthropicModelSettings(maxTokens, Optional.empty(), Thinking.MODEL_DEFAULT, Optional.empty());
    }

    /**
     * Reads {@code LLM_MODEL_RESPONSE_MAX_TOKENS} (default 1000), {@code LLM_ANTHROPIC_TEMPERATURE},
     * {@code LLM_ANTHROPIC_THINKING} and {@code LLM_ANTHROPIC_EFFORT}.
     */
    public static AnthropicModelSettings fromEnvironment() {
        return of(getRequiredEnvAsInteger(LLM_MODEL_RESPONSE_MAX_TOKENS, DEFAULT_MAX_TOKENS),
                System.getenv(LLM_ANTHROPIC_TEMPERATURE),
                System.getenv(LLM_ANTHROPIC_THINKING),
                System.getenv(LLM_ANTHROPIC_EFFORT));
    }

    /** Builds settings from raw string values, as read from configuration; blank means unset. */
    public static AnthropicModelSettings of(final long maxTokens, final String temperature, final String thinking,
                                            final String effort) {
        return new AnthropicModelSettings(maxTokens, parseTemperature(temperature), Thinking.parse(thinking),
                Optional.ofNullable(effort));
    }

    private static Optional<Double> parseTemperature(final String value) {
        if (isNullOrEmpty(value) || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(LLM_ANTHROPIC_TEMPERATURE + " must be a number, was '" + value + "'", e);
        }
    }
}
