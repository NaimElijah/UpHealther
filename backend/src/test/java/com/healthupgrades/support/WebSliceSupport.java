package com.healthupgrades.support;

import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.user.domain.model.Role;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * Shared wiring for the {@code @WebMvcTest} slices.
 *
 * <p>A slice test here runs the <em>real</em> {@code SecurityConfig} and {@code JwtAuthenticationFilter}
 * rather than a disabled chain. That matters: FR-5 — every endpoint but registration, login and the
 * health checks requires a valid token — has no other test, and a slice with security switched off
 * would assert the opposite of the requirement while looking green.
 *
 * <p>Authenticating therefore means presenting a token, exactly as a caller does. {@link #authenticateAs}
 * stubs the collaborator the filter consults so a chosen bearer token resolves to a chosen
 * principal; {@link #bearer} puts that token on a request. Only {@code BearerTokenAuthenticator} is
 * stubbed: whether a real token verifies is {@code JwtTokenProviderTest}'s business. A request sent without it is anonymous, and
 * the real authorization rules decide what happens to it.
 *
 * <p>Supplies a no-op {@link Tracer} because {@code GlobalExceptionHandler} takes one to stamp the trace
 * id onto an error body, and the tracing auto-configuration is not part of a web slice.
 */
@TestConfiguration
public class WebSliceSupport {

    /** The bearer token {@link #authenticateAs} makes valid. Any opaque string would do. */
    public static final String VALID_TOKEN = "a.valid.test.token";

    /**
     * A tracer that mints nothing. The error body's {@code traceId} is then absent rather than random,
     * which keeps a JSON assertion stable.
     */
    @Bean
    Tracer tracer() {
        return Tracer.NOOP;
    }

    // No Clock bean here: HealthUpgradesApplication already defines one and a second definition of the
    // same name fails the context outright. A slice that cares about a date-derived field (the
    // response's `overdue` flag) picks dates far enough from any plausible "today" to be unambiguous.

    /**
     * Makes {@link #VALID_TOKEN} resolve to a principal for the given user id.
     *
     * @param authenticator the slice's mocked {@code BearerTokenAuthenticator}
     * @param userId        the id the authenticated principal should carry, which is what every
     *                      controller threads down as the owner id
     * @return the principal, for a test that wants to assert against the same identity
     */
    public static SecurityUser authenticateAs(BearerTokenAuthenticator authenticator, UUID userId) {
        return authenticateAs(authenticator, userId, Role.USER);
    }

    /**
     * Makes {@link #VALID_TOKEN} resolve to a principal holding a particular role.
     *
     * @param authenticator the slice's mocked {@code BearerTokenAuthenticator}
     * @param userId        the id the authenticated principal should carry
     * @param role          the role the principal holds, which decides the administration paths
     * @return the principal, for a test that wants to assert against the same identity
     */
    public static SecurityUser authenticateAs(BearerTokenAuthenticator authenticator, UUID userId, Role role) {
        SecurityUser principal = AUser.principalFor(AUser.withRole(userId, role));
        when(authenticator.authenticate(VALID_TOKEN)).thenReturn(Optional.of(principal));
        return principal;
    }

    /** Puts the valid bearer token on a request, the way an authenticated client would. */
    public static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + VALID_TOKEN);
    }
}
