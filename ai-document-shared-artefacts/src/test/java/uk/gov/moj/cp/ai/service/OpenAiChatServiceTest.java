package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.exception.ChatServiceException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.openai.client.OpenAIClient;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseTextConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenAiChatServiceTest {

    private static final String DEPLOYMENT_NAME = "deploymentName";
    private OpenAIClient openAIClientMock;
    private OpenAiChatService chatService;

    @Test
    @DisplayName("Returns parsed response when valid input is provided")
    void returnsParsedResponseWhenValidInputIsProvided() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        String jsonResponse = "{\"key\":\"value\"}";
        Response response = mockResponse(jsonResponse);
        when(openAIClientMock.responses().create(any(ResponseCreateParams.class)))
                .thenReturn(response);

        var result = chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertTrue(result.isPresent());
        assertEquals("value", result.get().get("key"));
    }

    @Test
    @DisplayName("Returns parsed response when valid input is provided along with backticks")
    void returnsParsedResponseWhenValidInputIsProvidedAlongWithBackticks() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        String jsonResponse = "```json{\"key\":\"value\"}```";
        Response response = mockResponse(jsonResponse);
        when(openAIClientMock.responses().create(any(ResponseCreateParams.class)))
                .thenReturn(response);

        var result = chatService.callModel("systemInstruction", "userInstruction", Map.class);

        assertTrue(result.isPresent());
        assertEquals("value", result.get().get("key"));
    }

    @Test
    @DisplayName("Returns client specific exception when OpenAI client throws exception")
    void returnsEmptyOptionalWhenOpenAIClientThrowsException() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        when(openAIClientMock.responses().create(any(ResponseCreateParams.class)))
                .thenThrow(new RuntimeException("Client error"));

        assertThrows(RuntimeException.class, () -> chatService.callModel("systemInstruction", "userInstruction", Object.class));
    }

    @Test
    @DisplayName("Throws exception when endpoint is null or empty")
    void throwsExceptionWhenEndpointIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiChatService((String) null, DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiChatService("", DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Throws exception when deployment name is null or empty")
    void throwsExceptionWhenDeploymentNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiChatService("endpoint", null));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiChatService("endpoint", ""));
        assertEquals("Deployment name environment variable must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Returns service specific exception response JSON is invalid")
    void returnsEmptyOptionalWhenResponseJsonIsInvalid() throws Exception {
        initChatServiceWithMockClient(DEPLOYMENT_NAME);
        String invalidJsonResponse = "invalid_json";
        Response response = mockResponse(invalidJsonResponse);
        when(openAIClientMock.responses().create(any(ResponseCreateParams.class)))
                .thenReturn(response);

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
    @DisplayName("Non-reasoning deployments (gpt-4 family) set temperature and top_p to 0.0")
    void nonReasoningModelDeploymentSetsSamplingParameters() throws Exception {
        assertSamplingParameters("gpt-4o", false);
        assertSamplingParameters("gpt-4-turbo", false);
        assertSamplingParameters("deploymentName", false);
    }

    @Test
    @DisplayName("Verbosity defaults to low and reflects overrides on the text config")
    void verbosityIsSentOnTextConfig() throws Exception {
        initChatServiceWithMockClient("gpt-5");
        final Response response = mockResponse("{\"key\":\"value\"}");
        when(openAIClientMock.responses().create(any(ResponseCreateParams.class)))
                .thenReturn(response);

        // Default — env var unset, verbosity field defaults to "low"
        chatService.callModel("systemInstruction", "userInstruction", Map.class);
        ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(openAIClientMock.responses()).create(captor.capture());
        ResponseCreateParams params = captor.getValue();
        assertTrue(params.text().isPresent(), "text config should be present (default 'low')");
        assertEquals(ResponseTextConfig.Verbosity.LOW, params.text().get().verbosity().get());

        // Override via reflection — text.verbosity reflects the override
        setVerbosityField(chatService, "high");
        chatService.callModel("systemInstruction", "userInstruction", Map.class);

        captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(openAIClientMock.responses(), org.mockito.Mockito.times(2)).create(captor.capture());
        final ResponseCreateParams overridden = captor.getAllValues().get(1);
        assertTrue(overridden.text().isPresent());
        assertEquals(ResponseTextConfig.Verbosity.HIGH, overridden.text().get().verbosity().get());
    }

    private void assertSamplingParameters(final String deploymentName, final boolean isReasoning) throws Exception {
        initChatServiceWithMockClient(deploymentName);

        final Response response = mockResponse("{\"key\":\"value\"}");
        when(openAIClientMock.responses().create(any(ResponseCreateParams.class)))
                .thenReturn(response);

        chatService.callModel("systemInstruction", "userInstruction", Map.class);

        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(openAIClientMock.responses()).create(captor.capture());
        final ResponseCreateParams params = captor.getValue();

        assertTrue(params.maxOutputTokens().isPresent(), "max_output_tokens must always be set for " + deploymentName);
        if (isReasoning) {
            assertTrue(params.temperature().isEmpty(), "reasoning model must not set temperature: " + deploymentName);
            assertTrue(params.topP().isEmpty(), "reasoning model must not set top_p: " + deploymentName);
            assertTrue(params.reasoning().isPresent(), "reasoning model must set reasoning: " + deploymentName);
            assertEquals(Optional.of(ReasoningEffort.NONE), params.reasoning().get().effort(),
                    "reasoning model must default reasoning_effort to none: " + deploymentName);
            assertTrue(params.text().isPresent(), "reasoning model must set verbosity (text config): " + deploymentName);
        } else {
            assertEquals(Optional.of(0.0), params.temperature(), "non-reasoning model must set temperature=0.0: " + deploymentName);
            assertEquals(Optional.of(0.0), params.topP(), "non-reasoning model must set top_p=0.0: " + deploymentName);
            assertTrue(params.reasoning().isEmpty(), "non-reasoning model must not set reasoning: " + deploymentName);
            assertTrue(params.text().isEmpty(), "non-reasoning model must NOT set verbosity (gpt-4o rejects 'low'): " + deploymentName);
        }
    }

    private Response mockResponse(String jsonResponse) {
        final ResponseOutputText mockOutputText = mock(ResponseOutputText.class);
        when(mockOutputText.text()).thenReturn(jsonResponse);

        final ResponseOutputMessage.Content mockContent = mock(ResponseOutputMessage.Content.class);
        when(mockContent.outputText()).thenReturn(Optional.of(mockOutputText));

        final ResponseOutputMessage mockMessage = mock(ResponseOutputMessage.class);
        when(mockMessage.content()).thenReturn(List.of(mockContent));

        final ResponseOutputItem mockItem = mock(ResponseOutputItem.class);
        when(mockItem.message()).thenReturn(Optional.of(mockMessage));

        final Response response = mock(Response.class);
        when(response.output()).thenReturn(List.of(mockItem));
        when(response.status()).thenReturn(Optional.empty());
        when(response.incompleteDetails()).thenReturn(Optional.empty());
        return response;
    }

    private void initChatServiceWithMockClient(final String deploymentName) throws NoSuchFieldException, IllegalAccessException {
        chatService = new OpenAiChatService("https://example.openai.azure.com", deploymentName);
        openAIClientMock = mock(OpenAIClient.class, RETURNS_DEEP_STUBS);
        final Field clientField = OpenAiChatService.class.getDeclaredField("openAIClient");
        clientField.setAccessible(true);
        clientField.set(chatService, openAIClientMock);
    }

    private void setVerbosityField(final OpenAiChatService target, final String value) throws Exception {
        final Field f = OpenAiChatService.class.getDeclaredField("verbosity");
        f.setAccessible(true);
        f.set(target, value);
    }
}
