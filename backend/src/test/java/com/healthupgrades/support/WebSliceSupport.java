package com.healthupgrades.support;

import static org.mockito.Mockito.when;

import com.healthupgrades.auth.adapter.in.web.RefreshCookieProperties;
import com.healthupgrades.auth.adapter.in.web.RefreshCookies;
import com.healthupgrades.common.ratelimit.FixedWindowRateLimiter;
import com.healthupgrades.common.ratelimit.RateLimitProperties;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.user.domain.model.Role;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared wiring for the {@code @WebMvcTest} slices.
 *
 * <p>A slice test here runs the <em>real</em> {@code SecurityConfig} and {@code JwtAuthenticationFilter}
 * rather than a disabled chain. That matters: FR-5 — every endpoint except registration, sign-in,
 * refresh, sign-out, the actuator and the WebSocket handshake requires a valid token — has no other
 * test, and a slice with security switched off would assert the opposite of the requirement while
 * looking green.
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
     * The instant the cookie builder above believes it is.
     *
     * <p>Fixed, and not the application clock: a cookie's {@code Max-Age} is the distance from now to
     * the session expiry, so with a real clock the header would shrink by a second every second and
     * eventually go negative. Fixed here, a test can assert the number.
     */
    public static final Instant COOKIE_NOW = Instant.parse("2026-09-16T10:15:00Z");

    /**
     * The refresh cookie's name, as {@code application.yml} ships it. A slice builds its own
     * {@code RefreshCookies}, so {@code ShippedRefreshCookieConfigurationTest} is what pins the shipped one.
     */
    public static final String COOKIE_NAME = "__Secure-refresh_token";

    /**
     * The instant the rate limiter believes it is, for every request a slice sends. Fixed, so a slice's
     * attempts all land in one window and a refusal's wait is always the whole window.
     */
    public static final Instant LIMITER_NOW = Instant.parse("2026-09-16T10:15:00Z");

    /**
     * A tracer that mints nothing. The error body's {@code traceId} is then absent rather than random,
     * which keeps a JSON assertion stable.
     */
    @Bean
    Tracer tracer() {
        return Tracer.NOOP;
    }

    /**
     * The meter registry the rate-limit interceptor counts refusals on.
     *
     * <p>A web slice pulls in {@code RateLimitConfig} — {@code @WebMvcTest} includes every
     * {@code WebMvcConfigurer} — but not the {@code @Component}s it depends on, so without these two
     * beans every slice in the codebase fails to start.
     */
    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * The limiter, built from whatever limits the slice's properties say.
     *
     * <p>Deliberately fed from the bound properties rather than hard-wired permissive: a slice that
     * wants to <em>meet</em> the limit sets {@code app.rate-limit.limit} and gets a real limiter,
     * and one that does not care raises it and never notices.
     *
     * <p>Its clock stands still at {@link #LIMITER_NOW} (NFR-15). On the wall clock a slice that crossed
     * a minute boundary would get a fresh allowance partway through, and a {@code Retry-After} would
     * depend on when the test ran.
     */
    @Bean
    FixedWindowRateLimiter rateLimiter(RateLimitProperties properties) {
        return new FixedWindowRateLimiter(properties, Clock.fixed(LIMITER_NOW, ZoneOffset.UTC));
    }

    /**
     * The refresh cookie's settings, fixed here rather than bound from configuration.
     *
     * <p>An assertion about a cookie's attributes should be about the code that builds them, not about
     * whatever a YAML file happened to say when the test ran. The security attributes are not
     * configurable at all, so they come from {@link RefreshCookies} unchanged.
     */
    @Bean
    RefreshCookies refreshCookies() {
        return new RefreshCookies(
                new RefreshCookieProperties(COOKIE_NAME, "/api/auth"), Clock.fixed(COOKIE_NOW, ZoneOffset.UTC));
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
