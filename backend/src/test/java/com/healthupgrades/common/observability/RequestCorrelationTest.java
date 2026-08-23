package com.healthupgrades.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the correlation contract that issue #43 asked for: one id per request, on every line logged
 * while it is served, honouring an inbound {@code traceparent}, and gone again when the request ends.
 *
 * <p>The rig is deliberate. It boots Boot's <em>real</em> tracing auto-configuration — the thing whose
 * behaviour is being asserted — but none of the web server, datasource or Flyway, so {@code mvn test}
 * stays database-free. It then drives {@link ServerHttpObservationFilter} directly, twice, <b>on the
 * test thread</b>: that is what makes "a second request on the same thread does not inherit the first
 * one's id" a deterministic assertion rather than a race. Against a real Tomcat the thread cannot be
 * pinned, so {@code CorrelationIT} checks the wire contract and this checks the MDC lifecycle.
 *
 * <p>The guarantee itself belongs to the framework: {@code ServerHttpObservationFilter.doFilterInternal}
 * opens the observation scope in a try-with-resources, so the scope — and with it the MDC — closes even
 * when the chain throws.
 *
 * <p>One caveat worth knowing if this ever fails oddly: Boot installs the MDC bridge by calling
 * {@code ContextStorage.addWrapper(...)}, a JVM-global side effect that OpenTelemetry ignores once a
 * {@code Context} has been used. {@code StompTracingChannelInterceptorTest} also boots the tracing
 * auto-configuration in this fork, so whichever class runs first installs the wrapper both then rely on.
 * The wrapper is stateless, which is why that is safe; it fails loudly rather than silently if it ever
 * stops being.
 */
class RequestCorrelationTest {

    private static final String TRACE_ID = "traceId";
    private static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INBOUND_TRACEPARENT = "00-" + INBOUND_TRACE_ID + "-00f067aa0ba902b7-01";

    private final ApplicationContextRunner tracing = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                    org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class))
            .withPropertyValues("management.tracing.sampling.probability=1.0");

    @Test
    void GivenARequestIsBeingServed_WhenAnythingLogs_ThenEveryLineCarriesTheSameTraceId() {
        tracing.run(context -> {
            List<ILoggingEvent> logged = whileServing(context.getBean(io.micrometer.observation.ObservationRegistry.class),
                    new MockHttpServletRequest("GET", "/api/upgrades"), 2);

            assertThat(logged).hasSize(2);
            assertThat(logged).allSatisfy(event ->
                    assertThat(event.getMDCPropertyMap().get(TRACE_ID)).isNotBlank());
            assertThat(logged.get(0).getMDCPropertyMap().get(TRACE_ID))
                    .isEqualTo(logged.get(1).getMDCPropertyMap().get(TRACE_ID));
        });
    }

    @Test
    void GivenAnInboundTraceparent_WhenTheRequestIsServed_ThenThatTraceIdIsUsedRatherThanANewOne() {
        tracing.run(context -> {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/upgrades");
            request.addHeader("traceparent", INBOUND_TRACEPARENT);

            List<ILoggingEvent> logged =
                    whileServing(context.getBean(io.micrometer.observation.ObservationRegistry.class), request, 1);

            assertThat(logged).singleElement().satisfies(event ->
                    assertThat(event.getMDCPropertyMap().get(TRACE_ID)).isEqualTo(INBOUND_TRACE_ID));
        });
    }

    @Test
    void GivenARequestHasFinished_WhenTheSameThreadServesAnother_ThenItDoesNotInheritTheFirstId() {
        tracing.run(context -> {
            io.micrometer.observation.ObservationRegistry registry =
                    context.getBean(io.micrometer.observation.ObservationRegistry.class);

            List<ILoggingEvent> first = whileServing(registry, new MockHttpServletRequest("GET", "/api/upgrades"), 1);
            // The MDC belongs to the request, not to the thread that happened to serve it.
            assertThat(MDC.get(TRACE_ID)).isNull();

            List<ILoggingEvent> second = whileServing(registry, new MockHttpServletRequest("GET", "/api/upgrades"), 1);

            assertThat(second.get(0).getMDCPropertyMap().get(TRACE_ID))
                    .isNotBlank()
                    .isNotEqualTo(first.get(0).getMDCPropertyMap().get(TRACE_ID));
        });
    }

    /**
     * Runs one request through the observation filter and returns what was logged inside it.
     *
     * @param registry   the observation registry under test
     * @param request    the request to serve
     * @param logsToEmit how many lines the "application" writes while handling it
     */
    private static List<ILoggingEvent> whileServing(io.micrometer.observation.ObservationRegistry registry,
                                                    MockHttpServletRequest request,
                                                    int logsToEmit) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestCorrelationTest.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockFilterChain chain = new MockFilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                    for (int i = 0; i < logsToEmit; i++) {
                        logger.info("handling");
                    }
                }
            };
            new ServerHttpObservationFilter(registry).doFilter(request, new MockHttpServletResponse(), chain);
        } finally {
            logger.detachAppender(appender);
        }
        return new ArrayList<>(appender.list);
    }
}
