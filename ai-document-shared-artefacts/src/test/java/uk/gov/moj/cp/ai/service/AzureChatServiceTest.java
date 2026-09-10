package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.exception.ChatServiceException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.ai.openai.models.CompletionsFinishReason;
import com.azure.ai.openai.models.CompletionsUsage;
import com.azure.ai.openai.models.CompletionsUsageCompletionTokensDetails;
import com.azure.ai.openai.models.CompletionsUsagePromptTokensDetails;
import com.azure.ai.openai.models.ReasoningEffortValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AzureChatServiceTest {

    private static final String DEPLOYMENT_NAME = "deploymentName";
    private OpenAIClient openAIClientMock;
    private AzureChatService chatService;

    @Test
    @DisplayName("Returns parsed response when valid input is provided")
    void returnsParsedResponseWhenValidInputIsProvided() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        String jsonResponse = "{\"key\":\"value\"}";
        ChatCompletions chatCompletions = mockChatCompletions(jsonResponse);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        var result = chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertTrue(result.isPresent());
        assertEquals("value", result.get().get("key"));
    }

    @Test
    @DisplayName("Returns parsed response when valid input is provided along with backticks")
    void returnsParsedResponseWhenValidInputIsProvidedAlongWithBackticks() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        String jsonResponse = "```json{\"key\":\"value\"}```";
        ChatCompletions chatCompletions = mockChatCompletions(jsonResponse);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        var result = chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertTrue(result.isPresent());
        assertEquals("value", result.get().get("key"));
    }

    @Test
    @DisplayName("Returns client specific exception when OpenAI client throws exception")
    void returnsEmptyOptionalWhenOpenAIClientThrowsException() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenThrow(new RuntimeException("Client error"));

        assertThrows(RuntimeException.class, () -> chatService.callModel("systemInstruction", "userInstruction", Object.class));

    }

    @Test
    @DisplayName("Throws exception when endpoint is null or empty")
    void throwsExceptionWhenEndpointIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new AzureChatService((String) null, DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new AzureChatService("", DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Throws exception when deployment name is null or empty")
    void throwsExceptionWhenDeploymentNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new AzureChatService("endpoint", null));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new AzureChatService("endpoint", ""));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Returns service specific exception response JSON is invalid")
    void returnsEmptyOptionalWhenResponseJsonIsInvalid() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        String invalidJsonResponse = "invalid_json";
        ChatCompletions chatCompletions = mockChatCompletions(invalidJsonResponse);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        assertThrows(ChatServiceException.class, () -> chatService.callModel("systemInstruction", "userInstruction", Map.class));
    }

    @Test
    @DisplayName("Reasoning-model deployments (gpt-5/o-series) omit temperature/top_p and default reasoning_effort to none")
    void reasoningModelDeploymentOmitsSamplingParameters() throws Exception {
        assertSamplingParameters("gpt-5-mini", true);
        assertSamplingParameters("gpt5-deployment", true);
        assertSamplingParameters("o1-preview", true);
        assertSamplingParameters("o3", true);
        assertSamplingParameters("o4-mini", true);
    }

    @Test
    @DisplayName("Non-reasoning deployments (gpt-4 family) set temperature/top_p to 0.0 and no reasoning_effort")
    void nonReasoningModelDeploymentSetsSamplingParameters() throws Exception {
        assertSamplingParameters("gpt-4o", false);
        assertSamplingParameters("gpt-4-turbo", false);
        assertSamplingParameters("deploymentName", false);
    }

    private void assertSamplingParameters(final String deploymentName, final boolean isReasoning) throws Exception {
        initChatServiceWithMockClient(deploymentName);

        final ChatCompletions chatCompletions = mockChatCompletions("{\"key\":\"value\"}");
        when(openAIClientMock.getChatCompletions(eq(deploymentName), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        chatService.callModel("systemInstruction", "userInstruction", Map.class);

        final ArgumentCaptor<ChatCompletionsOptions> captor = ArgumentCaptor.forClass(ChatCompletionsOptions.class);
        verify(openAIClientMock).getChatCompletions(eq(deploymentName), captor.capture());
        final ChatCompletionsOptions options = captor.getValue();

        assertNotNull(options.getMaxCompletionTokens(), "max_completion_tokens must always be set for " + deploymentName);
        if (isReasoning) {
            assertNull(options.getTemperature(), "reasoning model must not set temperature: " + deploymentName);
            assertNull(options.getTopP(), "reasoning model must not set top_p: " + deploymentName);
            assertEquals(ReasoningEffortValue.fromString("none"), options.getReasoningEffort(),
                    "reasoning model must default reasoning_effort to none (LLM_REASONING_EFFORT unset): " + deploymentName);
        } else {
            assertEquals(0.0, options.getTemperature(), "non-reasoning model must set temperature=0.0: " + deploymentName);
            assertEquals(0.0, options.getTopP(), "non-reasoning model must set top_p=0.0: " + deploymentName);
            assertNull(options.getReasoningEffort(), "non-reasoning model must not set reasoning_effort: " + deploymentName);
        }
    }

    @Test
    @DisplayName("Reports token usage to the registered listener, mapping reasoning and cached-token details")
    void reportsTokenUsageToRegisteredListener() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        final ChatCompletions chatCompletions = mockChatCompletions("{\"key\":\"value\"}");
        final CompletionsUsage completionsUsage = mockUsage(120, 30, 5, 40);
        when(chatCompletions.getUsage()).thenReturn(completionsUsage);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        final AtomicReference<TokenUsage> captured = new AtomicReference<>();
        chatService.setTokenUsageListener(captured::set);
        chatService.callModel("systemInstruction", "userInstruction", Map.class);

        final TokenUsage usage = captured.get();
        assertNotNull(usage, "listener must receive the per-call token usage");
        assertEquals(120, usage.inputTokens());
        assertEquals(30, usage.outputTokens());
        assertEquals(5, usage.reasoningTokens());
        assertEquals(40, usage.cachedInputTokens());
    }

    @Test
    @DisplayName("Defaults reasoning and cached tokens to zero when the usage detail blocks are absent")
    void defaultsReasoningAndCachedTokensToZeroWhenDetailsAbsent() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        final ChatCompletions chatCompletions = mockChatCompletions("{\"key\":\"value\"}");
        final CompletionsUsage usage = mock(CompletionsUsage.class);
        when(usage.getPromptTokens()).thenReturn(120);
        when(usage.getCompletionTokens()).thenReturn(30);
        // detail blocks entirely absent from the response
        when(usage.getCompletionTokensDetails()).thenReturn(null);
        when(usage.getPromptTokensDetails()).thenReturn(null);
        when(chatCompletions.getUsage()).thenReturn(usage);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        final AtomicReference<TokenUsage> captured = new AtomicReference<>();
        chatService.setTokenUsageListener(captured::set);
        chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertEquals(new TokenUsage(120, 30, 0, 0), captured.get());
    }

    @Test
    @DisplayName("Defaults reasoning and cached tokens to zero when the detail blocks are present but empty")
    void defaultsReasoningAndCachedTokensToZeroWhenDetailFieldsNull() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        final ChatCompletions chatCompletions = mockChatCompletions("{\"key\":\"value\"}");
        final CompletionsUsage usage = mock(CompletionsUsage.class);
        when(usage.getPromptTokens()).thenReturn(120);
        when(usage.getCompletionTokens()).thenReturn(30);
        // detail blocks present but without the reasoning/cached fields
        when(usage.getCompletionTokensDetails()).thenReturn(mock(CompletionsUsageCompletionTokensDetails.class));
        when(usage.getPromptTokensDetails()).thenReturn(mock(CompletionsUsagePromptTokensDetails.class));
        when(chatCompletions.getUsage()).thenReturn(usage);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        final AtomicReference<TokenUsage> captured = new AtomicReference<>();
        chatService.setTokenUsageListener(captured::set);
        chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertEquals(new TokenUsage(120, 30, 0, 0), captured.get());
    }

    @Test
    @DisplayName("Does not invoke the listener when the response carries no usage block")
    void doesNotInvokeListenerWhenUsageAbsent() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        final ChatCompletions chatCompletions = mockChatCompletions("{\"key\":\"value\"}");
        when(chatCompletions.getUsage()).thenReturn(null);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        final AtomicReference<TokenUsage> captured = new AtomicReference<>();
        chatService.setTokenUsageListener(captured::set);
        chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertNull(captured.get(), "listener must not fire when the API returned no usage block");
    }

    @Test
    @DisplayName("Reports usage without error when no listener is registered")
    void reportsUsageWithoutErrorWhenNoListenerRegistered() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        final ChatCompletions chatCompletions = mockChatCompletions("{\"key\":\"value\"}");
        final CompletionsUsage completionsUsage = mockUsage(120, 30, 5, 40);
        when(chatCompletions.getUsage()).thenReturn(completionsUsage);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        var result = chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertTrue(result.isPresent());
    }

    private CompletionsUsage mockUsage(final int promptTokens, final int completionTokens,
                                       final int reasoningTokens, final int cachedTokens) {
        final CompletionsUsage usage = mock(CompletionsUsage.class);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        final CompletionsUsageCompletionTokensDetails completionDetails = mock(CompletionsUsageCompletionTokensDetails.class);
        when(completionDetails.getReasoningTokens()).thenReturn(reasoningTokens);
        final CompletionsUsagePromptTokensDetails promptDetails = mock(CompletionsUsagePromptTokensDetails.class);
        when(promptDetails.getCachedTokens()).thenReturn(cachedTokens);
        when(usage.getCompletionTokensDetails()).thenReturn(completionDetails);
        when(usage.getPromptTokensDetails()).thenReturn(promptDetails);
        return usage;
    }

    private ChatCompletions mockChatCompletions(String jsonResponse) {
        final ChatResponseMessage mockChatResponseMessage = mock(ChatResponseMessage.class);
        final ChatCompletions chatCompletions = mock(ChatCompletions.class);
        final ChatChoice mockChatChoice = mock(ChatChoice.class);
        when(chatCompletions.getChoices()).thenReturn(List.of(mockChatChoice));
        when(mockChatChoice.getMessage()).thenReturn(mockChatResponseMessage);
        when(mockChatChoice.getFinishReason()).thenReturn(CompletionsFinishReason.STOPPED);
        when(mockChatResponseMessage.getContent()).thenReturn(jsonResponse);
        return chatCompletions;
    }

    private void initChatServiceWithMockClient(final String deploymentName) throws NoSuchFieldException, IllegalAccessException {
        chatService = new AzureChatService("endpoint", deploymentName);
        openAIClientMock = mock(OpenAIClient.class);
        final Field clientField = AzureChatService.class.getDeclaredField("openAIClient");
        clientField.setAccessible(true);
        clientField.set(chatService, openAIClientMock);
    }
}
