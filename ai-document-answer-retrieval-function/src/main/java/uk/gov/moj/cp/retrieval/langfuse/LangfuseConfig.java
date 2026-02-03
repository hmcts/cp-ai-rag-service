package uk.gov.moj.cp.retrieval.langfuse;

import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import java.util.Base64;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public class LangfuseConfig {
    private static final Tracer TRACER;

    static {
        // Base64 string from Step 1
        String unencodedToken = getRequiredEnv("LANGFUSE_PUBLIC_KEY") + ":" + getRequiredEnv("LANGFUSE_SECRET_KEY");
        String encodedToken = Base64.getEncoder().encodeToString(unencodedToken.getBytes());
        String authHeader = "Basic " + encodedToken;

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(getRequiredEnv("LANGFUSE_BASE_URL") + "/api/public/otel/v1/traces")
                .addHeader("Authorization", authHeader)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .setResource(Resource.getDefault().toBuilder()
                        .put("service.name", "my-rag-app")
                        .build())
                .build();

        OpenTelemetrySdk otelSdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        TRACER = otelSdk.getTracer("langfuse-tracer");
    }

    public static Tracer getTracer() {
        return TRACER;
    }
}