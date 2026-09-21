package com.healthupgrades.common.ratelimit;

import com.healthupgrades.common.domain.exception.TooManyRequestsException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The metric tag the refusal counter carries, which has to be bounded by construction.
 *
 * <p>A Micrometer tag is a cardinality decision: one meter exists per distinct value, and the registry
 * feeds the public {@code /actuator/prometheus} payload. ADR-012 therefore forbids an unbounded tag,
 * and {@code getRequestURI()} — the raw target the caller sent — is unbounded by definition.
 *
 * <p><strong>This is defence in depth, not a live exploit.</strong> In the assembled application the
 * security chain refuses a target containing a semicolon with 400 long before this interceptor runs, so
 * the obvious vector does not currently reach here — {@code RateLimitedSignInTest} was written to prove
 * it and showed 400 rather than 429. What is being closed is the dependency: the tag's boundedness
 * rested on a framework default that a single line elsewhere could relax, and now rests on this method.
 *
 * <p>Tested against the interceptor directly rather than through MockMvc, precisely because the
 * firewall sits in between and this is a question about the interceptor.
 */
class RateLimitInterceptorTest {

    private static final int LIMIT = 1;

    private MeterRegistry meterRegistry;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                new RateLimitProperties(LIMIT, Duration.ofMinutes(1), 1000),
                Clock.fixed(java.time.Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC));
        interceptor = new RateLimitInterceptor(limiter, meterRegistry);
    }

    @Test
    void GivenALimitedPath_WhenAnAttemptIsRefused_ThenItIsCountedUnderThatPath() {
        spendAllowance("/api/auth/login", "203.0.113.1");

        assertThatThrownBy(() -> attempt("/api/auth/login", "203.0.113.1"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThat(tagsSeen()).containsExactly("/api/auth/login");
    }

    @Test
    void GivenATargetDressedUpToLookDifferent_WhenAttemptsAreRefused_ThenTheyShareOneBoundedTag() {
        // Five distinct raw targets that all routed here as the same endpoint. Tagged by the raw URI,
        // this is five meters and an attacker can keep going; tagged by the matched path, it is one.
        String client = "203.0.113.2";
        spendAllowance("/api/auth/login", client);
        IntStream.range(0, 5).forEach(probe ->
                assertThatThrownBy(() -> interceptor.preHandle(
                        request("/api/auth/login;probe=" + probe, client),
                        new MockHttpServletResponse(), null))
                        .isInstanceOf(TooManyRequestsException.class));

        assertThat(meterRegistry.find(RateLimitInterceptor.REFUSALS).counters())
                .as("one meter per distinct tag value; an unbounded tag would show five")
                .hasSize(1);
        assertThat(tagsSeen()).containsExactly(RateLimitInterceptor.OTHER);
    }

    @Test
    void GivenAnAttemptWithinTheAllowance_WhenItIsChecked_ThenNothingIsCountedAndItProceeds() {
        boolean proceeded =
                interceptor.preHandle(request("/api/auth/login", "203.0.113.3"),
                        new MockHttpServletResponse(), null);

        assertThat(proceeded).isTrue();
        assertThat(meterRegistry.find(RateLimitInterceptor.REFUSALS).counters()).isEmpty();
    }

    @Test
    void GivenARefusal_WhenItIsRaised_ThenItCarriesAWaitForTheCaller() {
        spendAllowance("/api/auth/register", "203.0.113.4");

        assertThatThrownBy(() -> attempt("/api/auth/register", "203.0.113.4"))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        refused -> assertThat(refused.retryAfterSeconds()).isPositive());
    }

    /** Uses up everything the client is allowed, so the next attempt is the refused one. */
    private void spendAllowance(String path, String client) {
        IntStream.range(0, LIMIT).forEach(i -> attempt(path, client));
    }

    private void attempt(String path, String client) {
        interceptor.preHandle(request(path, client), new MockHttpServletResponse(), null);
    }

    private java.util.List<String> tagsSeen() {
        return meterRegistry.find(RateLimitInterceptor.REFUSALS).counters().stream()
                .map(counter -> counter.getId().getTag("endpoint"))
                .toList();
    }

    private static MockHttpServletRequest request(String requestUri, String clientAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
        request.setRequestURI(requestUri);
        request.setRemoteAddr(clientAddress);
        return request;
    }
}
