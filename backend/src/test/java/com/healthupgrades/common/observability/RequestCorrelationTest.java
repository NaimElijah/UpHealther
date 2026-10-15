package com.healthupgrades.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryEventPublisherBeansApplicationListener;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;

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
 * <p>One caveat worth knowing if this ever fails oddly: the MDC bridge is a {@code ContextStorage} wrapper,
 * a JVM-global side effect that OpenTelemetry ignores once a {@code Context} has been used. Boot installs
 * it before any test runs, through a JUnit {@code TestExecutionListener} in
 * {@code spring-boot-micrometer-tracing-opentelemetry}. It holds each application context's event
 * publishers, which {@code OpenTelemetryEventPublisherBeansApplicationListener} adds when a context
 * refreshes and removes when it closes. That is why the runner below has to register the listener, and
 * why contexts from other test classes in this fork, such as {@code StompTracingChannelInterceptorTest}'s,
 * do not leak into this one.
 */
class RequestCorrelationTest {

    private static final String TRACE_ID = "traceId";
    private static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INBOUND_TRACEPARENT = "00-" + INBOUND_TRACE_ID + "-00f067aa0ba902b7-01";

    private final ApplicationContextRunner tracing = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration.class,
                    org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure
                            .OpenTelemetryTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class))
            // The listener that connects the context's Slf4JEventListener to the MDC bridge. A SpringApplication
            // loads it from spring.factories; a context runner loads nothing from there, so it is loaded here the
            // same way. Constructing it by hand would keep this test green if Boot ever stopped registering it,
            // and every log line would lose its trace id.
            .withInitializer(context ->
                    SpringFactoriesLoader.forDefaultResourceLocation(context.getClassLoader())
                            .load(ApplicationListener.class)
                            .stream()
                            .filter(OpenTelemetryEventPublisherBeansApplicationListener.class::isInstance)
                            .forEach(context::addApplicationListener))
            .withPropertyValues("management.tracing.sampling.probability=1.0");

    @Test
    void GivenARequestIsBeingServed_WhenAnythingLogs_ThenEveryLineCarriesTheSameTraceId() {
        tracing.run(context -> {
            List<ILoggingEvent> logged = whileServing(
                    context.getBean(io.micrometer.observation.ObservationRegistry.class),
                    new MockHttpServletRequest("GET", "/api/upgrades"),
                    2);

            assertThat(logged).hasSize(2);
            assertThat(logged)
                    .allSatisfy(event ->
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

            assertThat(logged)
                    .singleElement()
                    .satisfies(event ->
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
    private static List<ILoggingEvent> whileServing(
            io.micrometer.observation.ObservationRegistry registry, MockHttpServletRequest request, int logsToEmit)
            throws Exception {
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
