package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.function.UnaryOperator;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.AzureCliCredential;
import org.junit.jupiter.api.Test;

class AnthropicClientFactoryTest {

    private static final String LOCAL_SUBSCRIPTION = "AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION";

    private final TokenCredential shared = mock(TokenCredential.class);

    private static UnaryOperator<String> env(final Map<String, String> values) {
        return values::get;
    }

    @Test
    void usesSharedCredentialWhenNoLocalSubscriptionIsSet() {
        assertSame(shared, AnthropicClientFactory.gatewayCredential(env(Map.of()), shared));
    }

    @Test
    void usesSharedCredentialWhenLocalSubscriptionIsBlank() {
        assertSame(shared, AnthropicClientFactory.gatewayCredential(env(Map.of(LOCAL_SUBSCRIPTION, "  ")), shared));
    }

    @Test
    void usesAzureCliPinnedToSubscriptionWhenLocalSubscriptionIsSet() {
        final TokenCredential credential = AnthropicClientFactory.gatewayCredential(
                env(Map.of(LOCAL_SUBSCRIPTION, "00000000-0000-0000-0000-000000000001")), shared);

        assertInstanceOf(AzureCliCredential.class, credential);
    }

    @Test
    void refusesLocalSubscriptionInAzure() {
        final UnaryOperator<String> azureEnv = env(Map.of(
                LOCAL_SUBSCRIPTION, "00000000-0000-0000-0000-000000000001",
                AnthropicClientFactory.AZURE_HOSTED_INDICATOR, "instance-1"));

        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AnthropicClientFactory.gatewayCredential(azureEnv, shared));
        assertTrue(e.getMessage().contains(LOCAL_SUBSCRIPTION), e.getMessage());
    }

    @Test
    void usesSharedCredentialInAzureWhenLocalSubscriptionIsNotSet() {
        final UnaryOperator<String> azureEnv = env(Map.of(AnthropicClientFactory.AZURE_HOSTED_INDICATOR, "instance-1"));

        assertSame(shared, AnthropicClientFactory.gatewayCredential(azureEnv, shared));
    }
}
