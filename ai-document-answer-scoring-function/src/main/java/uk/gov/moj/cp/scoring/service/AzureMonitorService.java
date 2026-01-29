package uk.gov.moj.cp.scoring.service;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.monitor.opentelemetry.autoconfigure.AzureMonitorAutoConfigure;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AzureMonitorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureMonitorService.class);

    private final Meter meter; // Store the meter instance

    public static final String SCOPE_NAME = "ai-rag-service-meter";

    private final ConcurrentHashMap<String, DoubleHistogram> HISTOGRAM_CACHE = new ConcurrentHashMap<>();

    private AzureMonitorService() {
        LOGGER.info("Initializing service with OpenTelemetry SDK...");
        String connectionString = System.getenv("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING");

        AutoConfiguredOpenTelemetrySdkBuilder sdkBuilder = AutoConfiguredOpenTelemetrySdk.builder();
        AzureMonitorAutoConfigure.customize(sdkBuilder, connectionString);

        sdkBuilder.setResultAsGlobal().build();
        meter = GlobalOpenTelemetry.get().getMeter(SCOPE_NAME);
        LOGGER.info("AzureMonitorService initialized successfully.");
    }

    AzureMonitorService(final Meter meter) {
        this.meter = meter;
    }

    // 2. Static Inner Holder Class
    private static class SingletonHolder {
        // This is where the lazy, thread-safe initialization happens.
        // The JVM guarantees that the class is initialized safely and only once.
        private static final AzureMonitorService INSTANCE = new AzureMonitorService();
    }

    // 3. Public static method to get the instance.
    public static AzureMonitorService getInstance() {
        // No synchronization needed here.
        return SingletonHolder.INSTANCE;
    }

    public void publishHistogramScore(final String metricName, final String metricDescription, final double score, final String keyDimension, final String valueDimension) {
        final DoubleHistogram histogram = getDoubleHistogram(metricName, metricDescription);
        Attributes attributes = Attributes.of(AttributeKey.stringKey(keyDimension), valueDimension);
        histogram.record(score, attributes);
        LOGGER.info("Metrics have been exported successfully for query type: {} with score: {}", keyDimension, score);
    }

    private DoubleHistogram getDoubleHistogram(final String metricName, final String metricDescription) {
        final String cacheKey = (metricName + ":" + metricDescription).replaceAll("\\s+", "").trim();
        return HISTOGRAM_CACHE.computeIfAbsent(cacheKey, key -> {
                    LOGGER.info("Creating double histogram object for key '{}'", cacheKey);
                    return meter.histogramBuilder(metricName)
                            .setDescription(metricDescription)
                            .setUnit("1")
                            .build();
                }
        );
    }
}