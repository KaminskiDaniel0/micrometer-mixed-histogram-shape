package com.dkaminski.micrometer;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Two shapes under one metric name are merged into a single snapshot that contradicts its own type.
 *
 * <p>No {@code MeterFilter} takes part here. Both shapes are set in code, so these tests isolate the merge in
 * {@code MicrometerCollector.collect()} from the separate question of how a meter loses its configuration.
 *
 * <p>{@code MicrometerCollector.collect()} groups the data points of all children into
 * {@code Map<String, Family> families}, keyed by the convention name alone. The map is a <strong>raw type</strong>, so
 * {@code matchingFamily.addSamples(family.dataPointSnapshots)} adds data points of one shape to a list meant for the
 * other without any check. The {@code metricSnapshotFactory} of whichever family the loop meets first then wraps the
 * mixed list, and the exposition writer fails on the cast while rendering it.
 */
class MergedFamilyTest {

    private static final String FAMILY = "http.server.requests";

    private static final String SUMMARY_POINT = "SummarySnapshot$SummaryDataPointSnapshot";

    private static final String HISTOGRAM_POINT = "HistogramSnapshot$HistogramDataPointSnapshot";

    @Test
    @DisplayName("The merged family claims to be a summary and then holds a histogram data point")
    void theFamilyCanClaimToBeASummary() {

        final String message = castFailureOf("/plain/0", "/hist/0");

        assertThat(message).startsWith("class io.prometheus.metrics.model.snapshots." + HISTOGRAM_POINT)
            .contains("cannot be cast to class io.prometheus.metrics.model.snapshots." + SUMMARY_POINT);
    }

    @Test
    @DisplayName("The merged family claims to be a histogram and then holds a summary data point")
    void theFamilyCanClaimToBeAHistogram() {

        final String message = castFailureOf("/plain/13", "/hist/13");

        assertThat(message).startsWith("class io.prometheus.metrics.model.snapshots." + SUMMARY_POINT)
            .contains("cannot be cast to class io.prometheus.metrics.model.snapshots." + HISTOGRAM_POINT);
    }

    /**
     * Both tests above register the shapeless timer first and the bucketed one second, yet they fail in opposite
     * directions. The shape the merged family claims is therefore not decided by registration order. It follows the
     * iteration order of {@code MicrometerCollector.children}, a {@code ConcurrentHashMap} keyed by {@code Meter.Id},
     * so it depends on the tag values. Out of 40 tag pairs tried, 30 produced one direction and 10 the other.
     *
     * <p>That matters for a bug report: the type a broken family reports is arbitrary, and so is the writer method
     * named in the stack trace.
     */
    private static String castFailureOf(final String plainUri, final String bucketedUri) {

        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        Timer.builder(FAMILY)
            .tag("uri", plainUri)
            .publishPercentileHistogram(false)
            .register(registry)
            .record(5, TimeUnit.MILLISECONDS);

        Timer.builder(FAMILY)
            .tag("uri", bucketedUri)
            .publishPercentileHistogram(false)
            .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100))
            .register(registry)
            .record(5, TimeUnit.MILLISECONDS);

        return catchThrowableOfType(ClassCastException.class, registry::scrape).getMessage();
    }
}
