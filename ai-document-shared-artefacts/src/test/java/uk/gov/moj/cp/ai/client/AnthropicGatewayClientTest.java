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
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Queue<Reply> queuedReplies = new ConcurrentLinkedQueue<>();
    private volatile Reply defaultReply;

    private record Reply(int status, String body, Map<String, String> headers) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastExchange.set(exchange);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestCount.incrementAndGet();
            final Reply queued = queuedReplies.poll();
            final Reply reply = queued != null ? queued : defaultReply;
            final byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(reply.status(), bytes.length);
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

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + GATEWAY_PATH;
    }

    private AnthropicChatService chatService(final AnthropicModelSettings settings) {
        return new AnthropicChatService(
                AnthropicClientFactory.buildAiGatewayClient(baseUrl(), () -> "test-token", "test-key"),
                MODEL, settings, "AI gateway");
    }

    private void respondWith(final int status, final String body) {
        respondWith(status, body, Map.of());
    }

    private void respondWith(final int status, final String body, final Map<String, String> headers) {
        defaultReply = new Reply(status, body, headers);
    }

    private void respondFirstWith(final int status, final String body, final Map<String, String> headers) {
        queuedReplies.add(new Reply(status, body, headers));
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

    @Test
    void includesGatewayCorrelationIdInFailure() {
        respondWith(403, "{\"error\":{\"message\":\"not permitted\",\"type\":\"permission_error\",\"code\":\"model_not_permitted\"}}",
                Map.of("x-correlation-id", "corr-123"));

        final AnthropicChatService service = chatService(AnthropicModelSettings.withMaxTokens(100));

        final ChatServiceException e = assertThrows(ChatServiceException.class, () -> service.callModel("s", "u", String.class));
        assertTrue(e.getMessage().contains("HTTP 403"), e.getMessage());
        assertTrue(e.getMessage().contains("x-correlation-id: corr-123"), e.getMessage());
    }

    @Test
    void retriesAfterTheCappedWaitInsteadOfTheGatewayRetryAfter() throws Exception {
        respondFirstWith(429, "{\"error\":{\"message\":\"Token quota exceeded\",\"type\":\"rate_limit_error\",\"code\":\"token_quota_exceeded\"}}",
                Map.of("Retry-After", "60", "x-correlation-id", "corr-429"));
        respondWith(200, messageWithText("ok"));
        final AnthropicClient client = AnthropicOkHttpClient.builder()
                .backend(new AiGatewayBackend(baseUrl(), () -> "test-token", "test-key", Duration.ofMillis(100), Clock.systemUTC()))
                .maxRetries(1)
                .build();
        final AnthropicChatService service = new AnthropicChatService(client, MODEL, AnthropicModelSettings.withMaxTokens(100), "AI gateway");

        final long start = System.nanoTime();
        final Optional<String> answer = service.callModel("s", "u", String.class);
        final Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(Optional.of("ok"), answer);
        assertEquals(2, requestCount.get());
        assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0, "waited " + elapsed + " instead of the capped delay");
    }
}
