package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_GATEWAY_BASE_URL;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_GATEWAY_CHAT_MODEL;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_GATEWAY_JUDGE_MODEL;
import static uk.gov.moj.cp.ai.SharedSystemVariables.JUDGE_LLM_CHAT_SERVICE_PROVIDER;
import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_CHAT_SERVICE_PROVIDER;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The chat roles in the pipeline and the settings each reads.
 *
 * <p>Each role has one switch (its provider) and a set of settings per provider, so switching a
 * role between GPT and Claude changes only the switch; the other provider's settings stay in place.
 *
 * <table>
 *   <caption>Settings by role and provider</caption>
 *   <tr><th>Role</th><th>Switch</th><th>{@code openai} / {@code azure}</th><th>{@code claude-gateway}</th></tr>
 *   <tr><td>Answer generation</td><td>{@code LLM_CHAT_SERVICE_PROVIDER}</td>
 *       <td>{@code AZURE_OPENAI_ENDPOINT}, {@code AZURE_OPENAI_CHAT_DEPLOYMENT_NAME}</td>
 *       <td>{@code AI_GATEWAY_BASE_URL}, {@code AI_GATEWAY_CHAT_MODEL}</td></tr>
 *   <tr><td>Scoring judge</td><td>{@code JUDGE_LLM_CHAT_SERVICE_PROVIDER}, else {@code LLM_CHAT_SERVICE_PROVIDER}</td>
 *       <td>{@code AZURE_JUDGE_OPENAI_ENDPOINT}, {@code AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME}</td>
 *       <td>{@code AI_GATEWAY_BASE_URL}, {@code AI_GATEWAY_JUDGE_MODEL}</td></tr>
 * </table>
 *
 * <p>An unset switch means {@code openai}. The judge's Claude model has no fallback to the answer
 * generation model: it is always stated explicitly, so a change to one role never moves the other.
 */
public enum ChatRole {

    ANSWER_GENERATION("answer generation", LLM_CHAT_SERVICE_PROVIDER,
            "AZURE_OPENAI_ENDPOINT", "AZURE_OPENAI_CHAT_DEPLOYMENT_NAME", AI_GATEWAY_CHAT_MODEL),

    JUDGE("scoring judge", JUDGE_LLM_CHAT_SERVICE_PROVIDER,
            "AZURE_JUDGE_OPENAI_ENDPOINT", "AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME", AI_GATEWAY_JUDGE_MODEL);

    static final String PROVIDER_CLAUDE_GATEWAY = "claude-gateway";
    static final String DEFAULT_PROVIDER = "openai";

    /** The provider, endpoint and model a role resolves to. */
    public record Target(String provider, String endpoint, String model) {
    }

    private final String description;
    private final String providerKey;
    private final String azureEndpointKey;
    private final String azureModelKey;
    private final String gatewayModelKey;

    ChatRole(final String description, final String providerKey, final String azureEndpointKey,
             final String azureModelKey, final String gatewayModelKey) {
        this.description = description;
        this.providerKey = providerKey;
        this.azureEndpointKey = azureEndpointKey;
        this.azureModelKey = azureModelKey;
        this.gatewayModelKey = gatewayModelKey;
    }

    /** Resolves this role's target from the process environment. */
    public Target resolve() {
        return resolve(System::getenv);
    }

    /**
     * @throws IllegalStateException when a setting the chosen provider needs is missing
     */
    Target resolve(final UnaryOperator<String> environment) {
        final String provider = provider(environment);
        if (PROVIDER_CLAUDE_GATEWAY.equals(provider)) {
            return new Target(provider, required(environment, AI_GATEWAY_BASE_URL, provider),
                    required(environment, gatewayModelKey, provider));
        }
        return new Target(provider, required(environment, azureEndpointKey, provider),
                required(environment, azureModelKey, provider));
    }

    /** The role's provider, normalised; the judge follows the general switch when its own is unset. */
    String provider(final UnaryOperator<String> environment) {
        String value = environment.apply(providerKey);
        if (this == JUDGE && isBlank(value)) {
            value = environment.apply(LLM_CHAT_SERVICE_PROVIDER);
        }
        return isBlank(value) ? DEFAULT_PROVIDER : value.trim().toLowerCase(Locale.ROOT);
    }

    private String required(final UnaryOperator<String> environment, final String key, final String provider) {
        final String value = environment.apply(key);
        if (isBlank(value)) {
            throw new IllegalStateException("Required environment variable not set: " + key + " (needed by the "
                    + description + " with provider '" + provider + "')");
        }
        return value.trim();
    }

    private static boolean isBlank(final String value) {
        return isNullOrEmpty(value) || value.isBlank();
    }
}
