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

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.ai.openai.models.CompletionsFinishReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatServiceTest {

    private static final String DEPLOYMENT_NAME = "deploymentName";
    private OpenAIClient openAIClientMock;
    private ChatService chatService;

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
                () -> new ChatService((String) null, DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new ChatService("", DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Throws exception when deployment name is null or empty")
    void throwsExceptionWhenDeploymentNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ChatService("endpoint", null));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new ChatService("endpoint", ""));
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
    @DisplayName("Reasoning-model deployments (gpt-5/o-series) omit temperature and top_p")
    void reasoningModelDeploymentOmitsSamplingParameters() throws Exception {
        assertSamplingParameters("gpt-5-mini", true);
        assertSamplingParameters("gpt5-deployment", true);
        assertSamplingParameters("o1-preview", true);
        assertSamplingParameters("o3", true);
        assertSamplingParameters("o4-mini", true);
    }

    @Test
    @DisplayName("Non-reasoning deployments (gpt-4 family) set temperature and top_p to 0.0")
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
        } else {
            assertEquals(0.0, options.getTemperature(), "non-reasoning model must set temperature=0.0: " + deploymentName);
            assertEquals(0.0, options.getTopP(), "non-reasoning model must set top_p=0.0: " + deploymentName);
        }
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
        chatService = new ChatService("endpoint", deploymentName);
        openAIClientMock = mock(OpenAIClient.class);
        final Field clientField = ChatService.class.getDeclaredField("openAIClient");
        clientField.setAccessible(true);
        clientField.set(chatService, openAIClientMock);
    }
}
