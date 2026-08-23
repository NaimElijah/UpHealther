package com.healthupgrades.common.observability;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two pieces of wiring that have no other test and no visible failure mode.
 *
 * <p>Both are one-liners whose absence is silent. Delete the registry handed to the scheduler and the
 * three {@code @Scheduled} jobs keep working perfectly — they simply log without a trace id, including
 * the once-a-minute reminder dispatch that motivated this in the first place. Get the filter order
 * wrong and the response still returns, just without the header on the paths that need it most.
 * Neither shows up as a failure anywhere else, so they are asserted here.
 */
class ObservabilityConfigTest {

    private final ObservationRegistry registry = ObservationRegistry.create();
    private final ObservabilityConfig config = new ObservabilityConfig(registry);

    @Test
    void GivenScheduledJobsAreConfigured_WhenTheRegistrarIsBuilt_ThenItObservesThem() {
        // Spring wraps every @Scheduled run in an observation already, but only if it is given a
        // registry - and Boot 3.2.5 never gives it one.
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        config.configureTasks(registrar);

        assertThat(registrar.getObservationRegistry()).isSameAs(registry);
    }

    @Test
    void GivenTheTraceIdFilterIsRegistered_WhenItsOrderIsRead_ThenItSitsBetweenObservationAndSecurity() {
        // ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1) has to have opened the scope before this
        // filter reads the id, and Spring Security (-100) must not answer a 401 before it has run.
        int order = config.traceIdResponseHeaderFilter(Tracer.NOOP).getOrder();

        assertThat(order).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(order).isLessThan(-100);
    }
}
