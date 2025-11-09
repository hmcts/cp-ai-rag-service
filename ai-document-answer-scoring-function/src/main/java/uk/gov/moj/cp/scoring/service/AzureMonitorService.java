package uk.gov.moj.cp.scoring.service;

import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AzureMonitorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureMonitorService.class);

    private final Meter meter; // Store the meter instance

    public static final String SCOPE_NAME = "ai-rag-service-meter";

    private static AzureMonitorService INSTANCE;

    private AzureMonitorService() {
        final String azureInsightsConnectionString = System.getenv("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING");

        // Initialize and register OpenTelemetrySdk only once in the constructor
        AzureMonitorExporterBuilder exporterBuilder = new AzureMonitorExporterBuilder()
                .connectionString(azureInsightsConnectionString);

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(exporterBuilder.buildMetricExporter()).build())
                .build();

        OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();

        // Get the meter instance from the globally registered OpenTelemetry
        this.meter = GlobalOpenTelemetry.get().getMeter(SCOPE_NAME);
    }

    public static synchronized AzureMonitorService getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AzureMonitorService();
        }
        return INSTANCE;
    }

    public void publishHistogramScore(String metricName, String metricDescription, double score, String keyDimension, String valueDimension) {
        // Use the pre-initialized meter to record the metric
        final DoubleHistogram histogram = meter.histogramBuilder(metricName)
                .setDescription(metricDescription)
                .setUnit("1")
                .build();
        Attributes attributes = Attributes.of(AttributeKey.stringKey(keyDimension), valueDimension);
        histogram.record(score, attributes);
        LOGGER.info("Metrics have been exported successfully for query type: {} with score: {}", keyDimension, score);
    }
}