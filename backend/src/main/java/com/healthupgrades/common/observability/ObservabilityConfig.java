package com.healthupgrades.common.observability;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Wires the response header, the trace id on the container's error page and the scheduled-task
 * observations, none of which Spring Boot wires itself.
 *
 * <p>The other hand-wired piece, STOMP, is registered from {@code WebSocketConfig} because that is where
 * the channels are configured — look there too when auditing correlation coverage. Everything else comes
 * from the {@code micrometer-tracing-bridge-otel} dependency alone: the trace id in the log pattern, the
 * W3C {@code traceparent} propagation, the per-request scope. See
 * {@code docs/ADRs/ADR-007-request-correlation-through-micrometer-tracing.md}.
 */
@Configuration
@RequiredArgsConstructor
public class ObservabilityConfig implements SchedulingConfigurer {

    private final ObservationRegistry observationRegistry;

    /**
     * Registers {@link TraceIdResponseHeaderFilter} with an ordering that is stated rather than
     * inherited.
     *
     * <p>It has to sit between two filters it does not control: {@code ServerHttpObservationFilter}
     * (order {@code HIGHEST_PRECEDENCE + 1}), which opens the scope the id is read from, and Spring
     * Security's chain (order {@code -100}), whose rejections the header still has to reach. Hence
     * {@code HIGHEST_PRECEDENCE + 2}, and hence an explicit registration bean rather than
     * {@code @Component} — a component-scanned filter defaults to {@code LOWEST_PRECEDENCE}, which is
     * on the wrong side of Spring Security.
     */
    @Bean
    public FilterRegistrationBean<TraceIdResponseHeaderFilter> traceIdResponseHeaderFilter(Tracer tracer) {
        FilterRegistrationBean<TraceIdResponseHeaderFilter> registration =
                new FilterRegistrationBean<>(new TraceIdResponseHeaderFilter(tracer));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    /**
     * Replaces Boot's error attributes with ones that carry the trace id onto the container's error page.
     *
     * <p>Boot registers its own only when no {@code ErrorAttributes} bean exists, so declaring this one is
     * the whole of the switch. The concrete return type is deliberate: {@code DefaultErrorAttributes} is
     * also a {@code HandlerExceptionResolver}, and the dispatcher finds it by that type.
     */
    @Bean
    public TraceIdErrorAttributes errorAttributes() {
        return new TraceIdErrorAttributes();
    }

    /**
     * Puts every {@code @Scheduled} run inside an observation, so the lines it logs carry a trace id.
     *
     * <p>Spring already does the work — {@code ScheduledMethodRunnable} wraps each invocation in an
     * observation and closes the scope in a {@code finally} — but it needs a registry, and Boot 3.2.5
     * never supplies one. This one line covers every job, any added later included, which is why the
     * scheduler classes themselves are untouched: nothing for a new job to remember, and no
     * {@code try/finally} for anyone to get wrong.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setObservationRegistry(observationRegistry);
    }
}
