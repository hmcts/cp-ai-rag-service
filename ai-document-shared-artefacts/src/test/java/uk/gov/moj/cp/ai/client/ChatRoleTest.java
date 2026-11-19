package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.ai.client.ChatRole.Target;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ChatRoleTest {

    private static final String GATEWAY_URL = "https://gateway.example.test/ai/platform/v1/bedrock-messages";

    /** Both providers' settings for both roles, as an environment would hold them side by side. */
    private static Map<String, String> fullEnvironment() {
        final Map<String, String> env = new HashMap<>();
        env.put("AZURE_OPENAI_ENDPOINT", "https://generation.example.test/");
        env.put("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME", "gpt-4o-response-generation");
        env.put("AZURE_JUDGE_OPENAI_ENDPOINT", "https://judge.example.test");
        env.put("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME", "gpt-4o-judge");
        env.put("AI_GATEWAY_BASE_URL", GATEWAY_URL);
        env.put("AI_GATEWAY_CHAT_MODEL", "eu.anthropic.claude-sonnet-4-6");
        env.put("AI_GATEWAY_JUDGE_MODEL", "eu.anthropic.claude-opus-5-5");
        return env;
    }

    @Test
    void bothRolesDefaultToGptWhenNoSwitchIsSet() {
        final Map<String, String> env = fullEnvironment();

        assertEquals(new Target("openai", "https://generation.example.test/", "gpt-4o-response-generation"),
                ChatRole.ANSWER_GENERATION.resolve(env::get));
        assertEquals(new Target("openai", "https://judge.example.test", "gpt-4o-judge"),
                ChatRole.JUDGE.resolve(env::get));
    }

    @Test
    void answerGenerationSwitchesToClaudeWithOneSetting() {
        final Map<String, String> env = fullEnvironment();
        env.put("LLM_CHAT_SERVICE_PROVIDER", "claude-gateway");
        env.put("JUDGE_LLM_CHAT_SERVICE_PROVIDER", "openai");

        assertEquals(new Target("claude-gateway", GATEWAY_URL, "eu.anthropic.claude-sonnet-4-6"),
                ChatRole.ANSWER_GENERATION.resolve(env::get));
        assertEquals(new Target("openai", "https://judge.example.test", "gpt-4o-judge"),
                ChatRole.JUDGE.resolve(env::get));
    }

    @Test
    void judgeSwitchesToClaudeIndependentlyOfAnswerGeneration() {
        final Map<String, String> env = fullEnvironment();
        env.put("JUDGE_LLM_CHAT_SERVICE_PROVIDER", " Claude-Gateway ");

        assertEquals(new Target("claude-gateway", GATEWAY_URL, "eu.anthropic.claude-opus-5-5"),
                ChatRole.JUDGE.resolve(env::get));
        assertEquals("openai", ChatRole.ANSWER_GENERATION.resolve(env::get).provider());
    }

    @Test
    void judgeFollowsTheGeneralSwitchWhenItsOwnIsUnsetOrBlank() {
        final Map<String, String> env = fullEnvironment();
        env.put("LLM_CHAT_SERVICE_PROVIDER", "azure");
        assertEquals("azure", ChatRole.JUDGE.resolve(env::get).provider());

        env.put("JUDGE_LLM_CHAT_SERVICE_PROVIDER", "  ");
        assertEquals("azure", ChatRole.JUDGE.resolve(env::get).provider());
    }

    @Test
    void judgeClaudeModelHasNoFallbackToTheAnswerGenerationModel() {
        final Map<String, String> env = fullEnvironment();
        env.remove("AI_GATEWAY_JUDGE_MODEL");
        env.put("JUDGE_LLM_CHAT_SERVICE_PROVIDER", "claude-gateway");

        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> ChatRole.JUDGE.resolve(env::get));
        assertTrue(e.getMessage().contains("AI_GATEWAY_JUDGE_MODEL"), e.getMessage());
    }

    @Test
    void claudeNeedsTheGatewayBaseUrl() {
        final Map<String, String> env = fullEnvironment();
        env.remove("AI_GATEWAY_BASE_URL");
        env.put("LLM_CHAT_SERVICE_PROVIDER", "claude-gateway");

        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ChatRole.ANSWER_GENERATION.resolve(env::get));
        assertTrue(e.getMessage().contains("AI_GATEWAY_BASE_URL"), e.getMessage());
        assertTrue(e.getMessage().contains("answer generation"), e.getMessage());
    }

    @Test
    void gptDoesNotNeedAnyGatewaySettings() {
        final Map<String, String> env = fullEnvironment();
        env.remove("AI_GATEWAY_BASE_URL");
        env.remove("AI_GATEWAY_CHAT_MODEL");
        env.remove("AI_GATEWAY_JUDGE_MODEL");

        assertEquals("gpt-4o-response-generation", ChatRole.ANSWER_GENERATION.resolve(env::get).model());
        assertEquals("gpt-4o-judge", ChatRole.JUDGE.resolve(env::get).model());
    }

    @Test
    void gptStillNeedsItsOwnSettings() {
        final Map<String, String> env = fullEnvironment();
        env.remove("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME");

        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> ChatRole.JUDGE.resolve(env::get));
        assertTrue(e.getMessage().contains("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME"), e.getMessage());
    }
}
