package com.dkaminski.micrometer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Two shapes under one metric name, measured against Micrometer 1.18.0-RC1.
 *
 * <p>On 1.17.2 {@code MicrometerCollector.collect()} merged both shapes into one snapshot that contradicted its own
 * type, and the exposition writer failed on a cast. The whole scrape answered 500 and delivered no family at all.
 *
 * <p>On 1.18.0-RC1 the merge no longer happens. {@code PrometheusMeterRegistry} rejects the second shape at
 * registration time, logs a warning that names the meter id and the two types, and the scrape succeeds. What the
 * scrape then carries is the first shape only; the rejected meter is gone from the exposition.
 *
 * <p>The rejected meter is not gone from the application. It stays in {@code registry.getMeters()}, it accepts
 * recordings, and it is never mentioned again, because the registry logs every later conflict at debug level.
 */
class MergedFamilyTest {

    private static final String FAMILY = "http.server.requests";

    @Test
    @DisplayName("The second shape is refused at registration and the scrape stays healthy")
    void theConflictingShapeIsRefusedInsteadOfBreakingTheScrape() {

        // having
        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registerPlainTimer(registry, "/plain/0");
        registerBucketedTimer(registry, "/hist/0");

        // when / then
        assertThatCode(registry::scrape).doesNotThrowAnyException();

        final String scrape = registry.scrape();
        assertThat(scrape).contains("# TYPE http_server_requests_seconds summary")
            .contains("uri=\"/plain/0\"")
            .doesNotContain("uri=\"/hist/0\"");
        assertThatCode(() -> registry.scrape("application/openmetrics-text")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Registration order decides which shape survives, not the shape itself")
    void theFirstRegisteredShapeWinsTheFamily() {

        // having
        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registerBucketedTimer(registry, "/hist/0");
        registerPlainTimer(registry, "/plain/0");

        // when
        final String scrape = registry.scrape();

        // then
        assertThat(scrape).contains("# TYPE http_server_requests_seconds histogram")
            .contains("uri=\"/hist/0\"")
            .doesNotContain("uri=\"/plain/0\"");
    }

    @Test
    @DisplayName("The refused meter stays in the registry and keeps recording, invisible to the scrape")
    void theRefusedMeterKeepsRecordingWithoutBeingExposed() {

        // having
        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registerPlainTimer(registry, "/plain/0");
        final Timer refused = registerBucketedTimer(registry, "/hist/0");

        // when
        refused.record(7, TimeUnit.MILLISECONDS);

        // then
        assertThat(refused.count()).isEqualTo(2);
        assertThat(registry.getMeters()).extracting(Meter::getId).extracting(Meter.Id::getTags)
            .hasSize(2);
        assertThat(registry.scrape()).doesNotContain("uri=\"/hist/0\"");

        // Re-registering the same id returns the very same unexposed Timer, so a caller cannot notice the loss.
        assertThat(registerBucketedTimer(registry, "/hist/0")).isSameAs(refused);
    }

    private static Timer registerPlainTimer(final PrometheusMeterRegistry registry, final String uri) {

        final Timer timer = Timer.builder(FAMILY)
            .tag("uri", uri)
            .publishPercentileHistogram(false)
            .register(registry);
        timer.record(5, TimeUnit.MILLISECONDS);
        return timer;
    }

    private static Timer registerBucketedTimer(final PrometheusMeterRegistry registry, final String uri) {

        final Timer timer = Timer.builder(FAMILY)
            .tag("uri", uri)
            .publishPercentileHistogram(false)
            .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100))
            .register(registry);
        timer.record(5, TimeUnit.MILLISECONDS);
        return timer;
    }
}
