package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import uk.gov.moj.cp.ai.service.AzureEmbeddingService;
import uk.gov.moj.cp.ai.service.EmbeddingService;
import uk.gov.moj.cp.ai.service.OpenAiEmbeddingService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract for {@code EmbeddingServiceFactory} (FR-6; AC-13…AC-15) — a line-for-line analogue of
 * {@code ChatServiceFactoryTest}, including the package-private
 * {@code getInstance(endpoint, deploymentName, provider)} overload used to bypass the
 * {@code System.getenv} read of {@code EMBEDDING_SERVICE_PROVIDER}.
 */
class EmbeddingServiceFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String DEPLOYMENT_NAME = "deploymentName";

    @Test
    @DisplayName("AC-14: returns OpenAiEmbeddingService when provider is 'openai'")
    void returnsOpenAiEmbeddingServiceWhenProviderIsOpenai() {
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "openai");
        assertInstanceOf(OpenAiEmbeddingService.class, service);
    }

    @Test
    @DisplayName("AC-14: provider lookup is case-insensitive — 'OpenAI' returns OpenAiEmbeddingService")
    void providerLookupIsCaseInsensitiveForOpenai() {
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "OpenAI");
        assertInstanceOf(OpenAiEmbeddingService.class, service);
    }

    @Test
    @DisplayName("Returns AzureEmbeddingService when provider is 'azure'")
    void returnsAzureEmbeddingServiceWhenProviderIsAzure() {
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "azure");
        assertInstanceOf(AzureEmbeddingService.class, service);
    }

    @Test
    @DisplayName("Provider lookup is case-insensitive — 'AZURE' returns AzureEmbeddingService")
    void providerLookupIsCaseInsensitiveForAzure() {
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "AZURE");
        assertInstanceOf(AzureEmbeddingService.class, service);
    }

    @Test
    @DisplayName("AC-23: defaults to OpenAiEmbeddingService when provider is null")
    void defaultsToOpenAiEmbeddingServiceWhenProviderIsNull() {
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, null);
        assertInstanceOf(OpenAiEmbeddingService.class, service);
    }

    @Test
    @DisplayName("AC-23: defaults to OpenAiEmbeddingService when provider is empty")
    void defaultsToOpenAiEmbeddingServiceWhenProviderIsEmpty() {
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "");
        assertInstanceOf(OpenAiEmbeddingService.class, service);
    }

    @Test
    @DisplayName("AC-23: defaults to OpenAiEmbeddingService when provider is blank whitespace")
    void defaultsToOpenAiEmbeddingServiceWhenProviderIsBlank() {
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "   ");
        assertInstanceOf(OpenAiEmbeddingService.class, service);
    }

    @Test
    @DisplayName("AC-14: trims whitespace around provider value")
    void trimsWhitespaceAroundProviderValue() {
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "  openai  ");
        assertInstanceOf(OpenAiEmbeddingService.class, service);
    }

    @Test
    @DisplayName("AC-15: throws IllegalArgumentException naming the value and the accepted values")
    void throwsIllegalArgumentExceptionForUnknownProvider() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME, "oepnai"));
        assertEquals(
                "Unknown EMBEDDING_SERVICE_PROVIDER value: 'oepnai'. Expected one of: azure, openai.",
                exception.getMessage());
    }

    @Test
    @DisplayName("AC-23: public two-arg overload reads EMBEDDING_SERVICE_PROVIDER from env and defaults to openai")
    void publicOverloadReturnsAnEmbeddingService() {
        // Without setting the env var, the public overload should fall through to the OpenAI default.
        final EmbeddingService service = EmbeddingServiceFactory.getInstance(ENDPOINT, DEPLOYMENT_NAME);
        assertInstanceOf(OpenAiEmbeddingService.class, service);
    }
}
