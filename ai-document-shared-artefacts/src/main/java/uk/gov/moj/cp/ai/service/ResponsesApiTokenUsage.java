package uk.gov.moj.cp.ai.service;

import java.util.function.Consumer;

import com.openai.models.responses.ResponseUsage;
import org.slf4j.Logger;

/**
 * Shared mapping and dispatch of the OpenAI Responses API usage block, used by every
 * Responses-API-backed {@link ChatService} implementation (shared and harness-local) so the
 * field mapping to the provider-neutral {@link TokenUsage} is written exactly once.
 */
public final class ResponsesApiTokenUsage {

    private ResponsesApiTokenUsage() {
    }

    /**
     * Maps {@code usage} to a {@link TokenUsage}, logs it against {@code modelLabel} on
     * {@code logger} (the caller's own logger, so the INFO line stays attributed to the calling
     * service), and dispatches it to {@code listener} when one is registered. The log line runs
     * unconditionally (not gated on the listener): it is the production observability signal.
     */
    public static void report(final ResponseUsage usage, final Logger logger, final String modelLabel,
                              final Consumer<TokenUsage> listener) {
        final TokenUsage tokenUsage = new TokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.outputTokensDetails().reasoningTokens(),
                usage.inputTokensDetails().cachedTokens());
        logger.info("Token usage for {}: input={} output={} (reasoning={}) cachedInput={}",
                modelLabel, tokenUsage.inputTokens(), tokenUsage.outputTokens(),
                tokenUsage.reasoningTokens(), tokenUsage.cachedInputTokens());
        if (listener != null) {
            listener.accept(tokenUsage);
        }
    }
}
