package uk.gov.moj.cp.ai.service;

import java.util.function.Consumer;

/**
 * Optional side-channel on a {@link ChatService} implementation that surfaces the per-call
 * {@link TokenUsage} without changing the {@link ChatService#callModel} contract.
 *
 * <p>The listener is invoked once per API call, as soon as the response's usage block is
 * available — including calls that subsequently fail response validation (e.g. an empty or
 * filtered completion), since those calls are still billed. When no listener is registered the
 * implementation behaves exactly as before.
 */
public interface TokenUsageReporting {

    void setTokenUsageListener(Consumer<TokenUsage> listener);
}
