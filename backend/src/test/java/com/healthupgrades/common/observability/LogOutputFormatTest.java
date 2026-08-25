package com.healthupgrades.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what {@code logback-spring.xml} renders, in both of the formats it selects between.
 *
 * <p>This exists because of a specific, quiet failure mode. {@link RequestCorrelationTest} proves the
 * trace id reaches the <em>MDC</em>; nothing there notices if the log <em>output</em> stops printing it.
 * Before this project had a Logback configuration file, Boot's stock {@code CONSOLE_LOG_PATTERN}
 * interpolated {@code ${LOG_CORRELATION_PATTERN}} and the id appeared for free
 * ({@code docs/ADRs/ADR-007}, §Decision). A configuration file that writes its own {@code <pattern>}
 * instead of importing Boot's {@code defaults.xml} compiles, starts, logs happily — and correlates
 * nothing. That is a regression nobody would find until they needed the logs.
 *
 * <p>The rig boots a real {@link org.springframework.boot.SpringApplication} so that Boot's own
 * {@code LoggingApplicationListener} reads the shipped file exactly as it would in production, but with
 * no auto-configuration, no web server and no datasource, so {@code mvn test} stays database-free. It
 * then takes the configured appender's <em>encoder</em> and renders one event through it: asserting on
 * bytes the encoder produced is what makes this a test of the format rather than of the MDC.
 *
 * <p>Logging initialization is JVM-global, so {@link #restoreDefaultLogging()} puts the context back on
 * the plain-text configuration when the class finishes. Nothing else in the suite reads a root appender
 * — the other tests attach a {@code ListAppender} to a named logger — so the blast radius is nil either
 * way, but leaving a fork's logging in whichever state the last test wanted is a trap for the next one.
 */
class LogOutputFormatTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private static final String SERVICE_NAME = "health-upgrades-tracker";
    private static final String MESSAGE = "handling";

    @Test
    void GivenTheDefaultProfile_WhenALineIsRendered_ThenItIsPlainTextCarryingTheTraceId() {
        String rendered = renderOneLineUnderProfiles();

        assertThat(rendered)
                .contains(MESSAGE)
                .contains(TRACE_ID)
                .contains(SPAN_ID)
                .doesNotStartWith("{");
    }

    @Test
    void GivenTheJsonLogsProfile_WhenALineIsRendered_ThenItIsOneJsonObjectCarryingTheTraceId() throws Exception {
        String rendered = renderOneLineUnderProfiles("json-logs");

        JsonNode line = new ObjectMapper().readTree(rendered);
        assertThat(line.path("message").asText()).isEqualTo(MESSAGE);
        assertThat(line.path("level").asText()).isEqualTo("INFO");
        // The MDC is the only route by which correlation reaches the JSON, so includeMdc is the
        // json-logs equivalent of importing Boot's defaults.xml.
        assertThat(line.path("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(line.path("spanId").asText()).isEqualTo(SPAN_ID);
        assertThat(line.path("service").asText()).isEqualTo(SERVICE_NAME);
    }

    @AfterAll
    static void restoreDefaultLogging() {
        renderOneLineUnderProfiles();
    }

    /**
     * Boots an application under the given profiles so Boot configures Logback from the shipped
     * {@code logback-spring.xml}, then renders one event through the resulting encoder.
     *
     * @param profiles the profiles to activate; none for the plain-text branch
     * @return exactly what the encoder would have written to the console for that event
     */
    private static String renderOneLineUnderProfiles(String... profiles) {
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(NoBeans.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles)
                .properties("spring.application.name=" + SERVICE_NAME,
                        "spring.main.banner-mode=off")
                .run()) {
            return new String(configuredEncoder().encode(anEventInsideATrace()), StandardCharsets.UTF_8);
        }
    }

    /** The encoder the shipped configuration actually installed on the root logger. */
    private static Encoder<ILoggingEvent> configuredEncoder() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders();
        assertThat(appenders).as("the root logger has an appender to read the format from").hasNext();

        Appender<ILoggingEvent> appender = appenders.next();
        assertThat(appender)
                .as("the configured appender writes through an encoder")
                .isInstanceOf(OutputStreamAppender.class);
        return ((OutputStreamAppender<ILoggingEvent>) appender).getEncoder();
    }

    /**
     * An event carrying the MDC keys Micrometer's {@code Slf4JEventListener} writes while a request is
     * being traced. {@link LoggingEvent} reads the MDC when the map is first asked for, which is during
     * encoding, so the keys have to still be set at that point rather than at construction.
     */
    private static ILoggingEvent anEventInsideATrace() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        try {
            LoggingEvent event = new LoggingEvent(
                    LogOutputFormatTest.class.getName(), context.getLogger(LogOutputFormatTest.class),
                    Level.INFO, MESSAGE, null, null);
            event.getMDCPropertyMap();
            return event;
        } finally {
            MDC.clear();
        }
    }

    /** A context with nothing in it: this test needs Boot's logging initialization, not its beans. */
    @SpringBootConfiguration
    static class NoBeans {
    }
}
