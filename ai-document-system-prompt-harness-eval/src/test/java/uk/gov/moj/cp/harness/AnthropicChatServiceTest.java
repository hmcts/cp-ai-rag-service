package uk.gov.moj.cp.harness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class AnthropicChatServiceTest {

    @Test
    void acceptsBaseModelIdsServedInRegion() {
        for (final String model : List.of("anthropic.claude-sonnet-4-6", "anthropic.claude-opus-4-6-v1",
                "anthropic.claude-opus-5-5")) {
            assertDoesNotThrow(() -> AnthropicChatService.requireInRegionBedrockModel(model), model);
        }
    }

    @Test
    void rejectsCrossRegionProfilesAndNonBedrockIds() {
        for (final String model : List.of("eu.anthropic.claude-sonnet-4-6", "global.anthropic.claude-opus-4-6-v1",
                "us.anthropic.claude-haiku-4-5-20251001-v1:0", "claude-sonnet-4-6")) {
            assertThrows(IllegalArgumentException.class,
                    () -> AnthropicChatService.requireInRegionBedrockModel(model), model);
        }
    }

    @Test
    void rejectsMissingModel() {
        assertThrows(IllegalArgumentException.class, () -> AnthropicChatService.requireInRegionBedrockModel(null));
        assertThrows(IllegalArgumentException.class, () -> AnthropicChatService.requireInRegionBedrockModel(""));
    }
}
