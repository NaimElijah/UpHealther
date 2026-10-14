package com.healthupgrades.common.security;

import com.healthupgrades.support.PostgresIT;
import jakarta.servlet.FilterRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that {@link JwtAuthenticationFilter} runs only inside the security chain, never also as a
 * filter of its own in the servlet container (#238).
 *
 * <p>A second, container-level copy authenticates nothing extra, because {@code OncePerRequestFilter}
 * skips the nested run. It is a hazard all the same: if anything ever orders that copy ahead of the
 * chain, the copy authenticates first, the chain's {@code SecurityContextHolderFilter} replaces the
 * context, and the in-chain copy is skipped as already run, so every request is anonymous. That is how
 * 82 slice tests answered 401 during the Spring Boot 4.1 upgrade. No request-level assertion can tell
 * the two wirings apart while the order happens to be right, so this one reads the registrations the
 * running container actually holds. Only an embedded server has them, which is why this is an
 * {@code *IT}.
 *
 * <p>{@code @AutoConfigureTracing} and {@code @AutoConfigureMetrics} are here only so that this class
 * declares the same context as {@code CorrelationIT} and {@code ActuatorEndpointsIT}, and the suite
 * starts one server for all three.
 */
@AutoConfigureTracing
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtFilterRegistrationIT extends PostgresIT {

    private static final String SECURITY_CHAIN_REGISTRATION = "springSecurityFilterChain";

    /** Boot names a filter bean's registration after the bean. */
    private static final String JWT_FILTER_REGISTRATION = "jwtAuthenticationFilter";

    @Autowired
    private WebApplicationContext context;

    @Test
    void GivenTheRunningServer_WhenItsServletFiltersAreListed_ThenTheJwtFilterIsNotAmongThem() {
        Map<String, ? extends FilterRegistration> registrations =
                context.getServletContext().getFilterRegistrations();

        assertThat(registrations)
                .as("the list read is the populated one, holding the chain the JWT filter belongs in")
                .containsKey(SECURITY_CHAIN_REGISTRATION);
        // Checked by name and by class: a proxied bean would register under a generated subclass's
        // name, which the class check alone would miss, and a renamed bean would slip past the name.
        assertThat(registrations).doesNotContainKey(JWT_FILTER_REGISTRATION);
        assertThat(registrations.values())
                .extracting(FilterRegistration::getClassName)
                .doesNotContain(JwtAuthenticationFilter.class.getName());
    }
}
