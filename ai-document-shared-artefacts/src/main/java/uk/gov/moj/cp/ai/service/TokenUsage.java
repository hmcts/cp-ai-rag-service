package uk.gov.moj.cp.ai.service;

/**
 * Provider-neutral token usage for a single LLM call, taken verbatim from the API response's
 * usage block — never estimated client-side (tokenizers differ across model families, so the
 * response is the only definitive source for cost accounting).
 *
 * <p>Field semantics across providers:
 * <ul>
 *   <li>{@code reasoningTokens} — reasoning/thinking tokens, billed as output. A subset of
 *       {@code outputTokens} on every provider (OpenAI {@code output_tokens_details.reasoning_tokens},
 *       Azure OpenAI {@code completion_tokens_details.reasoning_tokens}, Anthropic
 *       {@code output_tokens_details.thinking_tokens}). Zero when the model did not reason or the
 *       provider omits the breakdown.</li>
 *   <li>{@code cachedInputTokens} — prompt tokens served from a provider-side cache. For
 *       OpenAI/Azure this is a subset of {@code inputTokens} ({@code cached_tokens}); for
 *       Anthropic ({@code cache_read_input_tokens}) it is reported <b>in addition to</b>
 *       {@code inputTokens}. Zero unless prompt caching is active.</li>
 * </ul>
 */
public record TokenUsage(long inputTokens, long outputTokens, long reasoningTokens, long cachedInputTokens) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
