package uk.gov.moj.cp.harness;

import static uk.gov.moj.cp.harness.HarnessEnv.env;
import static uk.gov.moj.cp.harness.HarnessEnv.requireEnv;

import uk.gov.moj.cp.ai.client.OpenAiClientFactory;
import uk.gov.moj.cp.ai.service.AzureChatService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

/**
 * DD-43422 (parent DD-43417) — Stage 0 content-filter parity spike. INVESTIGATION TOOL, run
 * on demand against a NON-PRODUCTION Azure OpenAI resource; never deployed, no production code
 * touched.
 *
 * <p>Question under test: {@code AzureChatService} (Azure SDK, chat-completions surface) logs
 * Azure content-filter diagnostics from {@code getPromptFilterResults()} /
 * {@code getContentFilterResults()} when a completion finishes {@code content_filter}. The
 * OpenAI-SDK path ({@code OpenAiChatService}, Responses API on {@code /openai/v1}) has no
 * equivalent — what does a content-filter trip look like through openai-java, and are the
 * per-category diagnostics recoverable?
 *
 * <p>Probes (evidence printed to stdout; the trip prompt itself is NEVER printed — only its
 * SHA-256 and category):
 * <ol>
 *   <li>Input-side filter trip via {@code client.responses().create} — expected to surface as
 *       an HTTP 400 {@link OpenAIServiceException}; dumps status/code/type/param and the FULL
 *       error body JSON (does {@code innererror.content_filter_result} survive the SDK?).
 *       Wall-clock duration shows whether the 400 was retried (RetryingHttpClient retries only
 *       X-Should-Retry/408/409/429/5xx — a fast single failure confirms fail-fast).</li>
 *   <li>Output truncation: benign prompt with a tiny {@code max_output_tokens} — is
 *       {@code incomplete_details.reason=max_output_tokens} distinguishable?</li>
 *   <li>Output-side filter annotation (best effort): if probe 1 unexpectedly returns 200, its
 *       status/incompleteDetails are recorded; no escalation to stronger prompts.</li>
 *   <li>Success-response annotations: benign call, dump {@code _additionalProperties()} and the
 *       raw JSON body — does ANY Azure filter annotation (prompt_filter_results /
 *       content_filter_results) appear on a successful /openai/v1 response?</li>
 *   <li>Azure-SDK side-by-side: the same trip prompt through {@link AzureChatService}, for the
 *       comparison table (or the known preview-api-version auth failure, itself evidence).</li>
 * </ol>
 *
 * <p>Run (mirrors run-harness.sh: export the module's .env first, {@code az login} for
 * DefaultAzureCredential):
 * <pre>
 *   set -a; source ai-document-system-prompt-harness-eval/.env; set +a
 *   mvn -q -pl ai-document-system-prompt-harness-eval -am -DskipTests install
 *   mvn -q -pl ai-document-system-prompt-harness-eval exec:java \
 *       -Dexec.mainClass=uk.gov.moj.cp.harness.ContentFilterSpikeTool
 * </pre>
 * Required env: {@code SPIKE_PRIMARY_DEPLOYMENT} (chat deployment to probe) and
 * {@code SPIKE_TRIP_PROMPT} (see below). Optional: {@code SPIKE_SECONDARY_DEPLOYMENT}
 * (blank/unset to skip).
 */
public final class ContentFilterSpikeTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BENIGN_SYSTEM = "You are a helpful assistant.";

    private static final String ABSENT = "(absent)";
    private static final String EXCEPTION_CLASS_LABEL = "  exception class = ";
    private static final String MESSAGE_LABEL = "  message         = ";

    /**
     * Env var supplying the deliberately-filtered probe input. Deliberately NOT embedded in
     * source: the runner provides a short prompt targeting a single filter category (e.g. a
     * graphic-violence description request), kept as mild as reliably trips the default
     * medium-threshold filter. It is never printed to evidence output — only its SHA-256 hash
     * (see {@link #sha256}).
     */
    private static final String TRIP_PROMPT_VAR = "SPIKE_TRIP_PROMPT";

    private ContentFilterSpikeTool() {
    }

    public static void main(final String[] args) {
        final String endpoint = requireEnv("AZURE_OPENAI_ENDPOINT");
        final String primary = requireEnv("SPIKE_PRIMARY_DEPLOYMENT");
        final String secondary = env("SPIKE_SECONDARY_DEPLOYMENT", "");
        final String tripPrompt = requireEnv(TRIP_PROMPT_VAR);

        out("=== DD-43422 content-filter parity spike ===");
        out("endpoint        : " + endpoint + "  (surface: /openai/v1, Responses API, bearer token)");
        out("primary model   : " + primary);
        out("secondary model : " + (secondary.isBlank() ? "(skipped)" : secondary));
        out("trip prompt     : SHA-256=" + sha256(tripPrompt)
                + "  (single filter category, supplied via " + TRIP_PROMPT_VAR + "; text withheld by design)");
        out("");

        final OpenAIClient client = OpenAiClientFactory.getInstance(endpoint);

        probe1InputTrip(client, primary, tripPrompt);
        if (!secondary.isBlank()) {
            probe1InputTrip(client, secondary, tripPrompt);
        }
        probe1bErrorBodyPassthrough(client, primary);
        probe2Truncation(client, primary);
        probe4SuccessAnnotations(client, primary);
        probe5AzureSdkSideBySide(endpoint, primary, tripPrompt);

        out("=== spike complete ===");
    }

    // ---- probe 1 (+3): input-side filter trip ---------------------------------------------------

    private static void probe1InputTrip(final OpenAIClient client, final String deployment, final String tripPrompt) {
        out("--- PROBE 1: input-side content-filter trip | deployment=" + deployment + " ---");
        final ResponseCreateParams params = ResponseCreateParams.builder()
                .model(deployment)
                .instructions(BENIGN_SYSTEM)
                .input(tripPrompt)
                .maxOutputTokens(200L)
                .build();
        final long startedAt = System.nanoTime();
        try {
            final Response response = client.responses().create(params);
            final long ms = elapsedMs(startedAt);
            // Probe 3 territory: the request was NOT rejected on the input side.
            out("RESULT: 200 OK (no input-side trip) in " + ms + " ms — output-side evidence follows (probe 3)");
            out("  response.status()            = " + response.status().map(Object::toString).orElse(ABSENT));
            out("  response.incompleteDetails() = " + response.incompleteDetails()
                    .map(d -> "reason=" + d.reason().map(Object::toString).orElse(ABSENT))
                    .orElse(ABSENT));
            out("  _additionalProperties keys   = " + response._additionalProperties().keySet());
            // The Azure-specific annotation block on the parsed model (never the output text):
            final JsonValue contentFilters = response._additionalProperties().get("content_filters");
            out("  content_filters annotation   = "
                    + (contentFilters == null ? ABSENT : prettyPrint(toJsonNode(contentFilters)).trim()));
        } catch (final OpenAIServiceException e) {
            final long ms = elapsedMs(startedAt);
            out("RESULT: rejected in " + ms + " ms (single fast failure ⇒ no retry; "
                    + "RetryingHttpClient retries only X-Should-Retry/408/409/429/5xx)");
            out(EXCEPTION_CLASS_LABEL + e.getClass().getName());
            out("  statusCode()    = " + e.statusCode());
            out("  code()          = " + e.code().orElse(ABSENT));
            out("  type()          = " + e.type().orElse(ABSENT));
            out("  param()         = " + e.param().orElse(ABSENT));
            final JsonNode body = toJsonNode(e.body());
            out("  body() JSON:");
            out(prettyPrint(body));
            final JsonNode cfr = body == null ? null
                    : body.at("/error/innererror/content_filter_result").isMissingNode()
                            ? (body.at("/innererror/content_filter_result").isMissingNode()
                                    ? null : body.at("/innererror/content_filter_result"))
                            : body.at("/error/innererror/content_filter_result");
            out("  innererror.content_filter_result present = " + (cfr != null));
            if (cfr != null) {
                out("  per-category severities: " + cfr.toString());
            }
        } catch (final Exception e) {
            out("RESULT: unexpected exception after " + elapsedMs(startedAt) + " ms");
            out(EXCEPTION_CLASS_LABEL + e.getClass().getName());
            out(MESSAGE_LABEL + e.getMessage());
        }
        out("");
    }

    // ---- probe 1b: error-body passthrough on a non-filter 400 -----------------------------------

    /**
     * When the resource's RAI policy is permissive (this STE resource pins {@code DisableFilter}
     * to every chat deployment), no prompt can provoke a content-filter 400. This probe provokes a
     * plain parameter-validation 400 instead, to demonstrate empirically that
     * {@link OpenAIServiceException#body()} carries Azure's error body JSON verbatim — the same
     * passthrough a {@code content_filter} 400 (with {@code innererror.content_filter_result})
     * would ride.
     */
    private static void probe1bErrorBodyPassthrough(final OpenAIClient client, final String deployment) {
        out("--- PROBE 1b: non-filter 400 (error-body passthrough) | deployment=" + deployment + " ---");
        final ResponseCreateParams params = ResponseCreateParams.builder()
                .model(deployment)
                .instructions(BENIGN_SYSTEM)
                .input("What is the capital of France?")
                .maxOutputTokens(100L)
                .temperature(5.0) // out of range [0,2] — deterministic 400 invalid_request_error
                .build();
        final long startedAt = System.nanoTime();
        try {
            client.responses().create(params);
            out("RESULT: unexpectedly succeeded (no 400)");
        } catch (final OpenAIServiceException e) {
            out("RESULT: rejected in " + elapsedMs(startedAt) + " ms (single fast failure ⇒ 400 not retried)");
            out(EXCEPTION_CLASS_LABEL + e.getClass().getName());
            out("  statusCode()    = " + e.statusCode());
            out("  code()          = " + e.code().orElse(ABSENT));
            out("  type()          = " + e.type().orElse(ABSENT));
            out("  param()         = " + e.param().orElse(ABSENT));
            out("  body() JSON:");
            out(prettyPrint(toJsonNode(e.body())));
        } catch (final Exception e) {
            out("RESULT: unexpected exception: " + e.getClass().getName() + ": " + e.getMessage());
        }
        out("");
    }

    // ---- probe 2: output truncation signal ------------------------------------------------------

    private static void probe2Truncation(final OpenAIClient client, final String deployment) {
        out("--- PROBE 2: output truncation (tiny max_output_tokens) | deployment=" + deployment + " ---");
        final ResponseCreateParams params = ResponseCreateParams.builder()
                .model(deployment)
                .instructions(BENIGN_SYSTEM)
                .input("Explain, at length, how the water cycle works.")
                .maxOutputTokens(16L) // Responses API documented minimum
                .build();
        try {
            final Response response = client.responses().create(params);
            out("  response.status()            = " + response.status().map(Object::toString).orElse(ABSENT));
            out("  response.incompleteDetails() = " + response.incompleteDetails()
                    .map(d -> "reason=" + d.reason().map(Object::toString).orElse(ABSENT))
                    .orElse(ABSENT));
        } catch (final Exception e) {
            out(EXCEPTION_CLASS_LABEL + e.getClass().getName());
            out(MESSAGE_LABEL + e.getMessage());
        }
        out("");
    }

    // ---- probe 4: success-response annotations --------------------------------------------------

    private static void probe4SuccessAnnotations(final OpenAIClient client, final String deployment) {
        out("--- PROBE 4: filter annotations on a SUCCESSFUL response | deployment=" + deployment + " ---");
        final ResponseCreateParams params = ResponseCreateParams.builder()
                .model(deployment)
                .instructions(BENIGN_SYSTEM)
                .input("What is the capital of France?")
                .maxOutputTokens(100L)
                .build();
        try {
            final Response response = client.responses().create(params);
            out("  response.status()          = " + response.status().map(Object::toString).orElse(ABSENT));
            out("  _additionalProperties()    = " + response._additionalProperties());
            // Raw body: the parsed model can only surface what the SDK maps; the raw JSON shows
            // everything Azure actually returned on the wire.
            try (HttpResponseFor<Response> raw = client.responses().withRawResponse().create(params)) {
                final JsonNode rawBody = readJson(raw.body());
                out("  raw HTTP status            = " + raw.statusCode());
                out("  raw body JSON:");
                out(prettyPrint(rawBody));
                final List<String> filterKeys = new ArrayList<>();
                collectFilterKeys(rawBody, "$", filterKeys);
                out("  filter-related keys in raw body = " + (filterKeys.isEmpty() ? "NONE" : filterKeys));
                out("  VERDICT: Azure filter annotations (prompt_filter_results/content_filter_results) on success = "
                        + (filterKeys.isEmpty() ? "NO" : "YES " + filterKeys));
            }
        } catch (final Exception e) {
            out(EXCEPTION_CLASS_LABEL + e.getClass().getName());
            out(MESSAGE_LABEL + e.getMessage());
        }
        out("");
    }

    // ---- probe 5: Azure SDK side-by-side --------------------------------------------------------

    private static void probe5AzureSdkSideBySide(final String endpoint, final String deployment, final String tripPrompt) {
        out("--- PROBE 5: same trip prompt via AzureChatService (Azure SDK, chat-completions) | deployment="
                + deployment + " ---");
        final long startedAt = System.nanoTime();
        try {
            final AzureChatService azureChatService = new AzureChatService(endpoint, deployment);
            azureChatService.callModel(BENIGN_SYSTEM, tripPrompt, String.class)
                    .ifPresentOrElse(
                            answer -> out("  RESULT: 200 with content (finish-reason diagnostics are in the "
                                    + "AzureChatService WARN log above); answer length=" + answer.length()),
                            () -> out("  RESULT: empty Optional returned"));
            out("  duration = " + elapsedMs(startedAt) + " ms");
        } catch (final Exception e) {
            out("  RESULT: exception after " + elapsedMs(startedAt) + " ms");
            for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
                out("  [" + t.getClass().getName() + "]");
                out("    " + String.valueOf(t.getMessage()).replace("\n", "\n    "));
            }
        }
        out("");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static void out(final String line) {
        System.out.println(line); // NOSONAR — spike evidence goes to stdout by design
    }

    private static long elapsedMs(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String sha256(final String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            return "(sha-256 unavailable: " + e.getMessage() + ")";
        }
    }

    private static JsonNode toJsonNode(final JsonValue body) {
        try {
            return body.convert(JsonNode.class);
        } catch (final Exception e) {
            out("  (body() not convertible to JsonNode: " + e.getMessage() + "; toString follows)");
            out("  " + body);
            return null;
        }
    }

    private static JsonNode readJson(final InputStream in) throws Exception {
        return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String prettyPrint(final JsonNode node) {
        if (node == null) {
            return "  (none)";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node).indent(2);
        } catch (final Exception e) {
            return "  (unprintable: " + e.getMessage() + ")";
        }
    }

    /** Collects the JSON paths of any key containing "filter" (case-insensitive). */
    private static void collectFilterKeys(final JsonNode node, final String path, final List<String> found) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (final Map.Entry<String, JsonNode> field : node.properties()) {
                final String childPath = path + "." + field.getKey();
                if (field.getKey().toLowerCase().contains("filter")) {
                    found.add(childPath);
                }
                collectFilterKeys(field.getValue(), childPath, found);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectFilterKeys(node.get(i), path + "[" + i + "]", found);
            }
        }
    }
}
