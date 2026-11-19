package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.service.AnthropicChatService;
import uk.gov.moj.cp.ai.service.AnthropicModelSettings;
import uk.gov.moj.cp.ai.service.TokenUsage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link AnthropicChatService} through a real gateway client against a local HTTP server, to
 * check the request the gateway receives and how its responses are handled.
 */
class AnthropicGatewayClientTest {

    private static final String GATEWAY_PATH = "/ai/platform/v1/bedrock-messages";
    private static final String MODEL = "eu.anthropic.claude-sonnet-4-6";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int responseStatus;
    private volatile String responseBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastExchange.set(exchange);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicChatService chatService(final AnthropicModelSettings settings) {
        final String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + GATEWAY_PATH;
        return new AnthropicChatService(
                AnthropicClientFactory.buildAiGatewayClient(baseUrl, () -> "test-token", "test-key"),
                MODEL, settings, "AI gateway");
    }

    private void respondWith(final int status, final String body) {
        responseStatus = status;
        responseBody = body;
    }

    private static String messageWithText(final String text) throws IOException {
        return MAPPER.writeValueAsString(Map.of(
                "id", "msg_1",
                "type", "message",
                "role", "assistant",
                "model", "claude-sonnet-4-6",
                "content", new Object[]{Map.of("type", "text", "text", text)},
                "stop_reason", "end_turn",
                "usage", Map.of("input_tokens", 14, "output_tokens", 4, "cache_read_input_tokens", 2)));
    }

    @Test
    void sendsMessagesRequestWithGatewayCredentials() throws Exception {
        respondWith(200, messageWithText("ok"));

        final Optional<String> answer = chatService(AnthropicModelSettings.withMaxTokens(200))
                .callModel("system prompt", "user prompt", String.class);

        assertEquals(Optional.of("ok"), answer);
        final HttpExchange exchange = lastExchange.get();
        assertEquals("POST", exchange.getRequestMethod());
        assertEquals(GATEWAY_PATH + "/v1/messages", exchange.getRequestURI().getPath());
        assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
        assertEquals("test-key", exchange.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Key"));
        assertEquals("2023-06-01", exchange.getRequestHeaders().getFirst("anthropic-version"));

        final JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals(MODEL, body.path("model").asText());
        assertEquals(200, body.path("max_tokens").asInt());
        assertEquals("system prompt", body.path("system").asText());
        assertEquals("user prompt", body.path("messages").path(0).path("content").asText());
        assertTrue(body.path("thinking").isMissingNode(), "thinking is omitted by default");
        assertTrue(body.path("temperature").isMissingNode(), "temperature is omitted by default");
    }

    @Test
    void sendsConfiguredModelSettings() throws Exception {
        respondWith(200, messageWithText("ok"));

        chatService(AnthropicModelSettings.of(500, "0", "disabled", "low")).callModel("s", "u", String.class);

        final JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals(0.0, body.path("temperature").asDouble());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals("low", body.path("output_config").path("effort").asText());
        assertTrue(body.path("top_p").isMissingNode(), "top_p is never sent");
    }

    @Test
    void parsesJsonResponsesIntoTheRequestedClass() throws Exception {
        respondWith(200, messageWithText("```json\n{\"score\": 4}\n```"));

        final Optional<Map> result = chatService(AnthropicModelSettings.withMaxTokens(100)).callModel("s", "u", Map.class);

        assertEquals(4, result.orElseThrow().get("score"));
    }

    @Test
    void reportsTokenUsage() throws Exception {
        respondWith(200, messageWithText("ok"));
        final AnthropicChatService service = chatService(AnthropicModelSettings.withMaxTokens(100));
        final AtomicReference<TokenUsage> usage = new AtomicReference<>();
        service.setTokenUsageListener(usage::set);

        service.callModel("s", "u", String.class);

        assertEquals(new TokenUsage(14, 4, 0, 2), usage.get());
    }

    @Test
    void rejectsEmptyResponse() throws Exception {
        respondWith(200, messageWithText(""));

        final AnthropicChatService service = chatService(AnthropicModelSettings.withMaxTokens(100));

        final ChatServiceException e = assertThrows(ChatServiceException.class, () -> service.callModel("s", "u", String.class));
        assertTrue(e.getMessage().contains("empty response"), e.getMessage());
    }

    @Test
    void reportsGatewayRejectionWithStatus() {
        respondWith(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid token\"}}");

        final AnthropicChatService service = chatService(AnthropicModelSettings.withMaxTokens(100));

        final ChatServiceException e = assertThrows(ChatServiceException.class, () -> service.callModel("s", "u", String.class));
        assertTrue(e.getMessage().contains("HTTP 401"), e.getMessage());
        assertTrue(e.getMessage().contains(MODEL), e.getMessage());
    }
}
