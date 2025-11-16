package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    private static final String DEPLOYMENT_NAME = "deploymentName";
    private OpenAIClient openAIClientMock;
    private ChatService chatService;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        chatService = ChatService.getInstance("endpoint", DEPLOYMENT_NAME);
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
    @DisplayName("Returns client specific exception when OpenAI client throws exception")
    void returnsEmptyOptionalWhenOpenAIClientThrowsException() throws ChatServiceException {
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenThrow(new RuntimeException("Client error"));

        assertThrows(RuntimeException.class, () -> chatService.callModel("systemInstruction", "userInstruction", Object.class));

    }

    @Test
    @DisplayName("Throws exception when endpoint is null or empty")
    void throwsExceptionWhenEndpointIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ChatService.getInstance(null, DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> ChatService.getInstance("", DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Throws exception when deployment name is null or empty")
    void throwsExceptionWhenDeploymentNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ChatService.getInstance("endpoint", null));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> ChatService.getInstance("endpoint", ""));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Returns service specific exception response JSON is invalid")
    void returnsEmptyOptionalWhenResponseJsonIsInvalid() throws Exception {
        String invalidJsonResponse = "invalid_json";
        ChatCompletions chatCompletions = mockChatCompletions(invalidJsonResponse);
        when(openAIClientMock.getChatCompletions(eq(DEPLOYMENT_NAME), any(ChatCompletionsOptions.class)))
                .thenReturn(chatCompletions);

        assertThrows(ChatServiceException.class, () -> chatService.callModel("systemInstruction", "userInstruction", Map.class));
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

    private void setOpenAIClientMockOnService() throws NoSuchFieldException, IllegalAccessException {
        openAIClientMock = mock(OpenAIClient.class);
        Field clientField = ChatService.class.getDeclaredField("openAIClient");
        clientField.setAccessible(true);
        clientField.set(chatService, openAIClientMock);
    }
}
