package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenUsageTest {

    @Test
    @DisplayName("totalTokens sums input and output tokens; reasoning and cached counts are excluded")
    void totalTokensSumsInputAndOutput() {
        final TokenUsage usage = new TokenUsage(120, 30, 5, 40);

        assertEquals(150, usage.totalTokens());
        assertEquals(120, usage.inputTokens());
        assertEquals(30, usage.outputTokens());
        assertEquals(5, usage.reasoningTokens());
        assertEquals(40, usage.cachedInputTokens());
    }
}
