package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import uk.gov.moj.cp.ai.service.AzureChatService;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.service.OpenAiChatService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatServiceFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String DEPLOYMENT_NAME = "deploymentName";

    @Test
    @DisplayName("Returns OpenAiChatService when provider is 'openai'")
    void returnsOpenAiChatServiceWhenProviderIsOpenai() {
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "openai");
        assertInstanceOf(OpenAiChatService.class, service);
    }

    @Test
    @DisplayName("Provider lookup is case-insensitive — 'OpenAI' returns OpenAiChatService")
    void providerLookupIsCaseInsensitiveForOpenai() {
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "OpenAI");
        assertInstanceOf(OpenAiChatService.class, service);
    }

    @Test
    @DisplayName("Returns AzureChatService when provider is 'azure'")
    void returnsAzureChatServiceWhenProviderIsAzure() {
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "azure");
        assertInstanceOf(AzureChatService.class, service);
    }

    @Test
    @DisplayName("Provider lookup is case-insensitive — 'AZURE' returns AzureChatService")
    void providerLookupIsCaseInsensitiveForAzure() {
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "AZURE");
        assertInstanceOf(AzureChatService.class, service);
    }

    @Test
    @DisplayName("Defaults to AzureChatService when provider is null")
    void defaultsToAzureChatServiceWhenProviderIsNull() {
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, null);
        assertInstanceOf(AzureChatService.class, service);
    }

    @Test
    @DisplayName("Defaults to AzureChatService when provider is empty")
    void defaultsToAzureChatServiceWhenProviderIsEmpty() {
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "");
        assertInstanceOf(AzureChatService.class, service);
    }

    @Test
    @DisplayName("Defaults to AzureChatService when provider is blank whitespace")
    void defaultsToAzureChatServiceWhenProviderIsBlank() {
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "   ");
        assertInstanceOf(AzureChatService.class, service);
    }

    @Test
    @DisplayName("Trims whitespace around provider value")
    void trimsWhitespaceAroundProviderValue() {
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "  openai  ");
        assertInstanceOf(OpenAiChatService.class, service);
    }

    @Test
    @DisplayName("Throws IllegalArgumentException for unrecognised provider value")
    void throwsIllegalArgumentExceptionForUnknownProvider() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "oepnai"));
        assertEquals(
                "Unknown LLM_CHAT_SERVICE_PROVIDER value: 'oepnai'. Expected one of: azure, openai.",
                exception.getMessage());
    }

    @Test
    @DisplayName("Public two-arg overload reads from env and returns some ChatService")
    void publicOverloadReturnsAChatService() {
        // Without setting the env var, the public overload should fall through to the Azure default.
        final ChatService service = ChatServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME);
        assertInstanceOf(AzureChatService.class, service);
    }
}
