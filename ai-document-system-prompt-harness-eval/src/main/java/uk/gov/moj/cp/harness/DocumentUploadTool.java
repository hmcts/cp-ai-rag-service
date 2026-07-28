package uk.gov.moj.cp.harness;

import static java.net.http.HttpResponse.BodyHandlers.ofString;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.azure.storage.blob.BlobClientBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uploads case documents through the service's real ingestion pipeline and reports the document
 * ids to evaluate against — companion to {@link TestHarness}, which consumes those ids via
 * {@code HARNESS_DOCUMENT_IDS}. Widening the evaluation document set is a two-step affair:
 * run this tool, then paste the printed ids into {@code .env}.
 *
 * <p>The tool drives the contract-first HTTP surface exactly as a caller would (no storage
 * back-door), so the document lands via the production path: {@code POST /document-upload}
 * (metadata-check function) → PUT the file bytes to the returned SAS URL → poll
 * {@code GET /document-upload/{documentReference}} (status-check function) until ingestion
 * reaches a terminal status.
 *
 * <p><b>Usage</b> (via {@code upload-document.sh}, which exports {@code .env} first):
 * <pre>./upload-document.sh /path/to/case-file.pdf [more files...]</pre>
 *
 * <p><b>Configuration</b> (environment):
 * <ul>
 *   <li>{@code HARNESS_UPLOAD_FUNCTION_BASE_URL} — base URL serving {@code POST /document-upload},
 *       INCLUDING any route prefix. With an APIM gateway fronting all the functions a single
 *       value does everything (e.g. {@code https://<gateway>/api-cp-ai-rag}); against bare
 *       function apps it is the metadata-check host (e.g. {@code http://localhost:7071/api}).
 *       Required.</li>
 *   <li>{@code HARNESS_STATUS_FUNCTION_BASE_URL} — base URL serving
 *       {@code GET /document-upload/{documentReference}}. Defaults to the upload base URL
 *       (the APIM single-gateway case); set it only when the status function lives on a
 *       different host, or to {@code none} to skip the ingestion wait entirely.</li>
 *   <li>{@code HARNESS_UPLOAD_FUNCTION_KEY} / {@code HARNESS_STATUS_FUNCTION_KEY} — optional
 *       {@code x-functions-key} values for function apps with key-protected routes.</li>
 *   <li>{@code HARNESS_CLIENT_ID} — optional client identity sent as the internal header
 *       (default name {@code X-Client-Id}); only needed where enforcement is switched on.</li>
 *   <li>{@code HARNESS_UPLOAD_METADATA} — optional comma-separated {@code key=value} pairs for
 *       the request's metadataFilter (each side ≤40 chars). Default: a random {@code caseId},
 *       matching how the integration tests upload.</li>
 *   <li>{@code HARNESS_UPLOAD_POLL_TIMEOUT_SECONDS} — ingestion wait per document
 *       (default 600; Document Intelligence → chunk → embed → index takes minutes).</li>
 * </ul>
 */
public final class DocumentUploadTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentUploadTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("INGESTION_SUCCESS", "INGESTION_FAILED", "FILE_SIZE_OVER_LIMIT");
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private DocumentUploadTool() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            LOGGER.error("Usage: upload-document.sh <file> [more files...]  (PDF/DOCX case documents)");
            System.exit(2);
        }
        final String uploadBase = trimTrailingSlash(requireEnv("HARNESS_UPLOAD_FUNCTION_BASE_URL"));
        // One gateway (APIM) fronting every function is the common case — default the status
        // host to the upload host; "none" opts out of the ingestion wait entirely.
        final String statusSetting = trimTrailingSlash(TestHarness.env("HARNESS_STATUS_FUNCTION_BASE_URL", uploadBase));
        final String statusBase = "none".equalsIgnoreCase(statusSetting) ? "" : statusSetting;
        if (statusBase.isEmpty()) {
            LOGGER.warn("Ingestion wait disabled (HARNESS_STATUS_FUNCTION_BASE_URL=none) — "
                    + "verify status before using the ids");
        }

        final Map<String, String> uploaded = new LinkedHashMap<>();
        boolean allSucceeded = true;
        for (final String arg : args) {
            final Path file = Paths.get(arg);
            if (!Files.isRegularFile(file)) {
                LOGGER.error("Not a readable file: {}", file.toAbsolutePath());
                allSucceeded = false;
                continue;
            }
            try {
                final String documentId = uploadOne(uploadBase, statusBase, file);
                uploaded.put(file.getFileName().toString(), documentId);
            } catch (final Exception e) {
                LOGGER.error("[upload] FAILED for {}: {}", file.getFileName(), e.getMessage(), e);
                allSucceeded = false;
            }
        }

        if (!uploaded.isEmpty()) {
            LOGGER.info("");
            LOGGER.info("================ UPLOADED DOCUMENTS ================");
            uploaded.forEach((name, id) -> LOGGER.info("  {}  ->  {}", name, id));
            LOGGER.info("");
            LOGGER.info("Add to .env (append to the existing list to widen the evaluation set):");
            LOGGER.info("  HARNESS_DOCUMENT_IDS={}", String.join(",", uploaded.values()));
        }
        if (!allSucceeded) {
            System.exit(1);
        }
    }

    /** Runs one document through initiate → SAS upload → (optional) ingestion poll; returns the document id. */
    private static String uploadOne(final String uploadBase, final String statusBase, final Path file) throws Exception {
        final String documentId = UUID.randomUUID().toString();
        final String documentName = file.getFileName().toString();

        final DocumentUploadRequest request = new DocumentUploadRequest()
                .documentId(documentId)
                .documentName(documentName);
        metadataPairs().forEach((k, v) -> request.addMetadataFilterItem(new MetadataFilter(k, v)));

        LOGGER.info("[upload] initiating upload: {} (documentId={})", documentName, documentId);
        final JsonNode location = postJson(uploadBase + "/document-upload", MAPPER.writeValueAsString(request),
                TestHarness.env("HARNESS_UPLOAD_FUNCTION_KEY", ""));
        final String storageUrl = location.path("storageUrl").asText("");
        final String documentReference = location.path("documentReference").asText(documentId);
        if (storageUrl.isEmpty()) {
            throw new IllegalStateException("Upload initiation returned no storageUrl: " + location);
        }

        LOGGER.info("[upload] PUTting {} bytes to the SAS URL", Files.size(file));
        new BlobClientBuilder().endpoint(storageUrl).buildClient().uploadFromFile(file.toString(), true);

        if (!statusBase.isEmpty()) {
            awaitIngestion(statusBase, documentReference, documentName);
        }
        return documentReference;
    }

    private static void awaitIngestion(final String statusBase, final String documentReference,
                                       final String documentName) throws Exception {
        final long timeoutSeconds = TestHarness.intEnv("HARNESS_UPLOAD_POLL_TIMEOUT_SECONDS", 600);
        final long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        final String statusKey = TestHarness.env("HARNESS_STATUS_FUNCTION_KEY", "");
        String status = "UNKNOWN";
        while (System.nanoTime() < deadline) {
            final JsonNode body = getJson(statusBase + "/document-upload/" + documentReference, statusKey);
            status = body.path("status").asText("UNKNOWN");
            if (TERMINAL_STATUSES.contains(status)) {
                if (!"INGESTION_SUCCESS".equals(status)) {
                    throw new IllegalStateException(documentName + " reached terminal status " + status
                            + (body.hasNonNull("reason") ? " — " + body.get("reason").asText() : ""));
                }
                LOGGER.info("[upload] {} ingested successfully (documentId={})", documentName, documentReference);
                return;
            }
            LOGGER.info("[upload] {} status: {} — waiting...", documentName, status);
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException(documentName + " did not reach a terminal ingestion status within "
                + timeoutSeconds + "s (last status: " + status + ")");
    }

    private static JsonNode postJson(final String url, final String body, final String functionKey) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return send(builder, functionKey, url);
    }

    private static JsonNode getJson(final String url, final String functionKey) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET();
        return send(builder, functionKey, url);
    }

    private static JsonNode send(final HttpRequest.Builder builder, final String functionKey, final String url)
            throws Exception {
        if (!functionKey.isEmpty()) {
            builder.header("x-functions-key", functionKey);
        }
        final String clientId = TestHarness.env("HARNESS_CLIENT_ID", "");
        if (!clientId.isEmpty()) {
            builder.header(TestHarness.env("CLIENT_IDENTITY_HEADER", "X-Client-Id"), clientId);
        }
        final HttpResponse<String> response = HTTP.send(builder.build(), ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url
                    + ": " + truncate(response.body()));
        }
        return MAPPER.readTree(response.body());
    }

    /** HARNESS_UPLOAD_METADATA ("k=v,k2=v2"); defaults to a random caseId, as the integration tests upload. */
    private static Map<String, String> metadataPairs() {
        final String spec = TestHarness.env("HARNESS_UPLOAD_METADATA", "");
        final Map<String, String> out = new LinkedHashMap<>();
        if (spec.isEmpty()) {
            out.put("caseId", UUID.randomUUID().toString());
            return out;
        }
        final List<String> bad = new ArrayList<>();
        for (final String pair : spec.split(",")) {
            final int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                bad.add(pair);
                continue;
            }
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        if (!bad.isEmpty()) {
            throw new IllegalStateException("Malformed HARNESS_UPLOAD_METADATA entries (need key=value): " + bad);
        }
        return out;
    }

    private static String requireEnv(final String key) {
        final String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + key
                    + " (set it in .env — see .env.sample)");
        }
        return v;
    }

    private static String trimTrailingSlash(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(final String s) {
        return s == null ? "" : (s.length() <= 500 ? s : s.substring(0, 499) + "…");
    }
}
