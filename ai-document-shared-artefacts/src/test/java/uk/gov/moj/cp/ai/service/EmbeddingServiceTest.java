package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmbeddingServiceTest {

    @Test
    void constructorUsingApiKeyThrowsExceptionWhenArgumentsAreNullOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService("", "apiKey", "deploymentName"));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService(null, "apiKey", "deploymentName"));

        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService("endpoint", null, "deploymentName"));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService("endpoint", "", "deploymentName"));

        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService("endpoint", "apiKey", null));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService("endpoint", "apiKey", ""));
    }

    @Test
    void constructorSettingUpManagedIdentityThrowsExceptionWhenArgumentsAreNullOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService("", "deploymentName"));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService(null, "deploymentName"));

        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService("endpoint", null));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingService("endpoint", ""));
    }
}
