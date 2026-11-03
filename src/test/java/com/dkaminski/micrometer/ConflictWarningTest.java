package com.dkaminski.micrometer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How often the rejection of a conflicting meter is announced at WARN level on Micrometer 1.18.0-RC1.
 *
 * <p>The answer is once per JVM. The first rejected meter is named at WARN and says so itself: "Note that subsequent
 * logs will be logged at debug level." Every later rejection is logged at DEBUG, across metric families and across
 * registries. A service that trips this twice therefore learns the name of one meter and nothing about the other.
 */
class ConflictWarningTest {

    private ListAppender<ILoggingEvent> appender;

    private Logger logger;

    @BeforeEach
    void before() {

        logger = (Logger) LoggerFactory.getLogger(PrometheusMeterRegistry.class);
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void afterEach() {

        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("Only the first rejected meter of the JVM is named at WARN level")
    void theRejectionIsAnnouncedOncePerJvm() {

        // having
        rejectOneMeterIn(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        // when
        rejectOneMeterIn(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        // then
        final List<ILoggingEvent> rejections = appender.list.stream()
            .filter(event -> event.getFormattedMessage().contains("registration has failed"))
            .toList();
        assertThat(rejections).hasSize(2);
        assertThat(rejections.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(rejections.getLast().getLevel()).isEqualTo(Level.DEBUG);
    }

    /**
     * Registers a shapeless timer and a bucketed one under one fresh family name, so the second is rejected.
     */
    private static void rejectOneMeterIn(final PrometheusMeterRegistry registry) {

        final String family = "family." + UUID.randomUUID();
        Timer.builder(family).tag("uri", "/plain").publishPercentileHistogram(false).register(registry);
        Timer.builder(family)
            .tag("uri", "/bucketed")
            .publishPercentileHistogram(false)
            .serviceLevelObjectives(Duration.ofMillis(50))
            .register(registry);
    }
}
