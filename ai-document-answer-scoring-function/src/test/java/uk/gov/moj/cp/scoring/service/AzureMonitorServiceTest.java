package uk.gov.moj.cp.scoring.service;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.Meter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AzureMonitorServiceTest {

    private Meter meterMock;
    private DoubleHistogramBuilder histogramBuilderMock;
    private DoubleHistogram histogramMock;
    private AzureMonitorService azureMonitorService;

    @BeforeEach
    void setUp() {
        meterMock = mock(Meter.class);
        histogramBuilderMock = mock(DoubleHistogramBuilder.class);
        histogramMock = mock(DoubleHistogram.class);

        when(meterMock.histogramBuilder(anyString())).thenReturn(histogramBuilderMock);
        when(histogramBuilderMock.setDescription(anyString())).thenReturn(histogramBuilderMock);
        when(histogramBuilderMock.setUnit(anyString())).thenReturn(histogramBuilderMock);
        when(histogramBuilderMock.build()).thenReturn(histogramMock);

        azureMonitorService = new AzureMonitorService(meterMock);
    }

    @Test
    @DisplayName("Publishes histogram score successfully with valid inputs")
    void publishesHistogramScoreSuccessfullyWithValidInputs() {
        String metricName = "responseTime";
        String metricDescription = "Time taken to respond";
        double score = 123.45;
        String keyDimension = "queryType";
        String valueDimension = "search";

        azureMonitorService.publishHistogramScore(metricName, metricDescription, score, keyDimension, valueDimension);

        verify(histogramMock).record(score, Attributes.of(AttributeKey.stringKey(keyDimension), valueDimension));
        verify(histogramBuilderMock).setUnit("1");
    }

    @Test
    @DisplayName("Creates and caches histogram when not already cached")
    void createsAndCachesHistogramWhenNotAlreadyCached() {
        String metricName = "responseTime";
        String metricDescription = "Time taken to respond";

        azureMonitorService.publishHistogramScore(metricName, metricDescription, 1.0, "key", "value");

        verify(meterMock).histogramBuilder(metricName);
        verify(histogramBuilderMock).setDescription(metricDescription);
        verify(histogramBuilderMock).setUnit("1");
        verify(histogramBuilderMock).build();
    }

    @Test
    @DisplayName("Reuses cached histogram for the same metric name and description")
    void reusesCachedHistogramForSameMetricNameAndDescription() {
        String metricName = "responseTime";
        String metricDescription = "Time taken to respond";

        azureMonitorService.publishHistogramScore(metricName, metricDescription, 1.0, "key", "value");
        azureMonitorService.publishHistogramScore(metricName, metricDescription, 2.0, "key", "value2");

        // Should only build once
        verify(histogramBuilderMock, times(1)).build();
        verify(histogramMock, times(2)).record(anyDouble(), any(Attributes.class));
    }

    @Test
    @DisplayName("Handles different metric names and descriptions with separate histograms")
    void handlesDifferentMetricNamesAndDescriptionsWithSeparateHistograms() {
        DoubleHistogramBuilder anotherBuilderMock = mock(DoubleHistogramBuilder.class);
        DoubleHistogram anotherHistogramMock = mock(DoubleHistogram.class);

        when(meterMock.histogramBuilder("metric2")).thenReturn(anotherBuilderMock);
        when(anotherBuilderMock.setDescription("desc2")).thenReturn(anotherBuilderMock);
        when(anotherBuilderMock.setUnit("1")).thenReturn(anotherBuilderMock);
        when(anotherBuilderMock.build()).thenReturn(anotherHistogramMock);

        azureMonitorService.publishHistogramScore("metric1", "desc1", 1.0, "key", "value");
        azureMonitorService.publishHistogramScore("metric2", "desc2", 2.0, "key", "value2");

        verify(histogramBuilderMock).build();
        verify(anotherBuilderMock).build();
        verify(histogramMock).record(1.0, Attributes.of(AttributeKey.stringKey("key"), "value"));
        verify(anotherHistogramMock).record(2.0, Attributes.of(AttributeKey.stringKey("key"), "value2"));
    }
}
