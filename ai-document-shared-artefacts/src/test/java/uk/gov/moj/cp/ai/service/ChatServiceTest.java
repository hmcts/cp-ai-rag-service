package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatMessage;
import com.azure.ai.openai.models.ChatRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    private static final String DEPLOYMENT_NAME = "deploymentName";
    private OpenAIClient openAIClientMock;
    private ChatService chatService;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        chatService = new ChatService("endpoint", "apiKey", DEPLOYMENT_NAME);
        // After creating chatService and openAIClientMock
        setOpenAIClientMockOnService();
    }

    @Test
    @DisplayName("Returns parsed response when valid input is provided")
    void returnsParsedResponseWhenValidInputIsProvided() throws Exception {
        String jsonResponse = "{\"key\":\"value\"}";
        ChatCompletions chatCompletions = mockChatCompletions(jsonResponse);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        var result = chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertTrue(result.isPresent());
        assertEquals("value", result.get().get("key"));
    }

    @Test
    @DisplayName("Returns empty optional when OpenAI client throws exception")
    void returnsEmptyOptionalWhenOpenAIClientThrowsException() {
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenThrow(new RuntimeException("Client error"));

        Optional<Object> result = chatService.callModel("systemInstruction", "userInstruction", Object.class);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Throws exception when endpoint is null or empty")
    void throwsExceptionWhenEndpointIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ChatService(null, "apiKey", DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new ChatService("", "apiKey", DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Throws exception when deployment name is null or empty")
    void throwsExceptionWhenDeploymentNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ChatService("endpoint", "apiKey", null));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new ChatService("endpoint", "apiKey", ""));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Returns empty optional when response JSON is invalid")
    void returnsEmptyOptionalWhenResponseJsonIsInvalid() throws Exception {
        String invalidJsonResponse = "invalid_json";
        ChatCompletions chatCompletions = mockChatCompletions(invalidJsonResponse);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        Optional<Object> result = chatService.callModel("systemInstruction", "userInstruction", Object.class);

        assertTrue(result.isEmpty());
    }

    private ChatCompletions mockChatCompletions(String jsonResponse) {
        final ChatMessage chatMessage = new ChatMessage(ChatRole.ASSISTANT).setContent(jsonResponse);
        final ChatCompletions chatCompletions = mock(ChatCompletions.class);
        final ChatChoice mockChatChoice = mock(ChatChoice.class);
        when(chatCompletions.getChoices()).thenReturn(List.of(mockChatChoice));
        when(mockChatChoice.getMessage()).thenReturn(chatMessage);
        return chatCompletions;
    }

    private void setOpenAIClientMockOnService() throws NoSuchFieldException, IllegalAccessException {
        openAIClientMock = mock(OpenAIClient.class);
        Field clientField = ChatService.class.getDeclaredField("openAIClient");
        clientField.setAccessible(true);
        clientField.set(chatService, openAIClientMock);
    }
}
