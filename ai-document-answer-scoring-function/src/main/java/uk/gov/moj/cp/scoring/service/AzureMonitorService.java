package uk.gov.moj.cp.scoring.service;

import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureMonitorService.class.getName());

    private final String azureInsightsConnectionString;

    public AzureMonitorService(String azureInsightsConnectionString) {
        this.azureInsightsConnectionString = azureInsightsConnectionString;
    }

    public void publishHistogramScore(String scopeName, String metricName, String metricDescription, double score, String keyDimension, String valueDimension) {
        AzureMonitorExporterBuilder exporterBuilder = new AzureMonitorExporterBuilder()
                .connectionString(azureInsightsConnectionString);

        final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(exporterBuilder.buildMetricExporter()).build())
                .build();

        try (final OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal()) {

            Meter meter = openTelemetrySdk.getMeter(scopeName);
            final DoubleHistogram histogram = meter.histogramBuilder(metricName)
                    .setDescription(metricDescription)
                    .setUnit("1")
                    .build();
            Attributes attributes = Attributes.of(AttributeKey.stringKey(keyDimension), valueDimension);
            histogram.record(score, attributes);
            LOGGER.info("Metrics have been exported successfully for query type: {} with score: {}", keyDimension, score);
        }
    }
}
