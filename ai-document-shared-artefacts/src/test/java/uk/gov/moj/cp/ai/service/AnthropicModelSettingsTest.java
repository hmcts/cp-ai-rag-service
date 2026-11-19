package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import uk.gov.moj.cp.ai.service.AnthropicModelSettings.Thinking;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class AnthropicModelSettingsTest {

    @Test
    void blankValuesMeanUnset() {
        final AnthropicModelSettings settings = AnthropicModelSettings.of(800, " ", "", null);

        assertEquals(800, settings.maxTokens());
        assertEquals(Optional.empty(), settings.temperature());
        assertEquals(Thinking.MODEL_DEFAULT, settings.thinking());
        assertEquals(Optional.empty(), settings.effort());
    }

    @Test
    void parsesAllValues() {
        final AnthropicModelSettings settings = AnthropicModelSettings.of(4000, "0", " Disabled ", " LOW ");

        assertEquals(Optional.of(0.0), settings.temperature());
        assertEquals(Thinking.DISABLED, settings.thinking());
        assertEquals(Optional.of("low"), settings.effort());
    }

    @Test
    void parsesAdaptiveThinking() {
        assertEquals(Thinking.ADAPTIVE, AnthropicModelSettings.of(100, null, "adaptive", null).thinking());
    }

    @Test
    void rejectsUnknownThinkingValue() {
        assertThrows(IllegalArgumentException.class, () -> AnthropicModelSettings.of(100, null, "enabled", null));
    }

    @Test
    void rejectsNonNumericTemperature() {
        assertThrows(IllegalArgumentException.class, () -> AnthropicModelSettings.of(100, "warm", null, null));
    }

    @Test
    void rejectsNonPositiveMaxTokens() {
        assertThrows(IllegalArgumentException.class, () -> AnthropicModelSettings.withMaxTokens(0));
    }
}
