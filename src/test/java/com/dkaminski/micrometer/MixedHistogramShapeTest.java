package com.dkaminski.micrometer;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.PropertiesMeterFilter;
import org.springframework.boot.micrometer.metrics.autoconfigure.ServiceLevelObjectiveBoundary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * One meter family, two shapes, measured against Micrometer 1.18.0-RC1.
 *
 * <p>The question these tests answer is what a meter that misses its {@code MeterFilter} costs on the new version.
 * On 1.17.2 it cost the whole scrape: the exposition writer failed on a cast and the endpoint answered 500. On
 * 1.18.0-RC1 the scrape stays up, and the cost moves to the series that are no longer exposed.
 *
 * <p>{@link #baselineScrapesCleanly()} shows the healthy case. {@link #aMeterRegisteredBeforeTheFilterTakesTheWholeFamilyDownWithIt()}
 * produces the mixed family on purpose and measures what reaches the scrape.
 * {@link #concurrentRegistrationIsSafe()} is a negative control.
 */
class MixedHistogramShapeTest {

    private static final String FAMILY = "http.server.requests";

    private static final String[] BOUNDARIES = {
        "50ms", "75ms", "113ms", "170ms", "255ms", "383ms", "575ms", "863ms",
        "1000ms", "1295ms", "1943ms", "2915ms", "4373ms", "10s", "30s", "60s"
    };

    @Test
    @DisplayName("With the filter in place from the start, every timer of the family is histogram-shaped")
    void baselineScrapesCleanly() {

        final PrometheusMeterRegistry registry = newRegistry();
        registry.config().meterFilter(sloFilter());

        registerTimers(registry, 10);

        assertThatCode(registry::scrape).doesNotThrowAnyException();
        assertThat(bucketCounts(registry.scrape())).allMatch(count -> count == BOUNDARIES.length + 1);
    }

    @Test
    @DisplayName("A timer registered before the filter keeps the family on its shape and shuts the rest out")
    void aMeterRegisteredBeforeTheFilterTakesTheWholeFamilyDownWithIt() {

        // having
        final PrometheusMeterRegistry registry = newRegistry();

        // The one meter that misses the filter. In a real application this is an ordinary request.
        final Timer early = registerTimer(registry, "/entity/{entity-id}");
        assertThat(early.takeSnapshot().histogramCounts()).isEmpty();

        registry.config().meterFilter(sloFilter());

        // when
        registerTimers(registry, 10);

        // then
        // Re-registering the same id returns the very same unconfigured Timer. The registry marks the id stale when
        // the filter arrives late, but it never rebuilds the meter, so the family keeps both shapes.
        final Timer afterFilter = registerTimer(registry, "/entity/{entity-id}");
        assertThat(afterFilter).isSameAs(early);
        assertThat(afterFilter.takeSnapshot().histogramCounts()).isEmpty();

        // The scrape no longer throws. It carries the shape of the meter that registered first, and that one meter is
        // the unconfigured one, so the ten correctly configured timers are refused and never reach Prometheus.
        assertThatCode(registry::scrape).doesNotThrowAnyException();
        assertThatCode(() -> registry.scrape("application/openmetrics-text")).doesNotThrowAnyException();

        final String scrape = registry.scrape();
        assertThat(scrape).contains("# TYPE http_server_requests_seconds summary")
            .contains("uri=\"/entity/{entity-id}\"")
            .doesNotContain("uri=\"/resource/0\"");
        assertThat(bucketCounts(scrape)).isEmpty();

        // All eleven meters are alive in the registry; the scrape shows one of them.
        assertThat(registry.getMeters()).hasSize(11);
        assertThat(seriesCount(scrape)).isEqualTo(1);
    }

    @Test
    @DisplayName("Registering the same family from many threads at once does not lose the configuration")
    void concurrentRegistrationIsSafe() throws InterruptedException {

        final PrometheusMeterRegistry registry = newRegistry();
        registry.config().meterFilter(sloFilter());

        final int threads = 32;
        final int idsPerThread = 25;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            IntStream.range(0, threads).forEach(thread -> pool.submit(() -> {
                try {
                    start.await();
                    IntStream.range(0, idsPerThread)
                        .forEach(id -> registerTimer(registry, "/resource/" + (id % idsPerThread)));
                }
                catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                finally {
                    done.countDown();
                }
            }));

            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThatCode(registry::scrape).doesNotThrowAnyException();
        assertThat(bucketCounts(registry.scrape())).allMatch(count -> count == BOUNDARIES.length + 1);
    }

    private static PrometheusMeterRegistry newRegistry() {

        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    private static PropertiesMeterFilter sloFilter() {

        final MetricsProperties properties = new MetricsProperties();
        properties.getDistribution().getPercentilesHistogram().put(FAMILY, false);
        properties.getDistribution()
            .getSlo()
            .put(FAMILY, Arrays.stream(BOUNDARIES).map(ServiceLevelObjectiveBoundary::valueOf)
                .toArray(ServiceLevelObjectiveBoundary[]::new));
        return new PropertiesMeterFilter(properties);
    }

    private static void registerTimers(final PrometheusMeterRegistry registry, final int count) {

        IntStream.range(0, count).forEach(index -> registerTimer(registry, "/resource/" + index));
    }

    private static Timer registerTimer(final PrometheusMeterRegistry registry, final String uri) {

        final Timer timer = Timer.builder(FAMILY)
            .tag("uri", uri)
            .tag("status", "200")
            .tag("outcome", "SUCCESS")
            .publishPercentileHistogram(false)
            .register(registry);
        timer.record(5, TimeUnit.MILLISECONDS);
        return timer;
    }

    /**
     * The number of series of the family in a scrape, counted over the {@code _count} lines of the summary shape and
     * the {@code _count} lines of the histogram shape alike.
     */
    private static long seriesCount(final String scrape) {

        return scrape.lines().filter(line -> line.startsWith(FAMILY.replace('.', '_') + "_seconds_count")).count();
    }

    /**
     * The number of {@code le} buckets per series in a scrape, so a mixed family shows up as differing counts.
     */
    private static List<Long> bucketCounts(final String scrape) {

        final List<Long> counts = new ArrayList<>();
        scrape.lines()
            .filter(line -> line.startsWith(FAMILY.replace('.', '_') + "_seconds_bucket"))
            .map(line -> line.substring(0, line.indexOf("le=")))
            .distinct()
            .forEach(prefix -> counts.add(scrape.lines().filter(line -> line.startsWith(prefix)).count()));
        return counts;
    }
}
