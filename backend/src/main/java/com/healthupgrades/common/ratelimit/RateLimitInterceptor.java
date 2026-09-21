package com.healthupgrades.common.ratelimit;

import com.healthupgrades.common.domain.exception.TooManyRequestsException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses an anonymous authentication attempt once its client address has made too many.
 *
 * <p><strong>A {@link HandlerInterceptor}, not a filter, and not a {@code @Component}.</strong> A
 * filter bean is registered automatically by Boot <em>and</em> by any chain that names it, so it runs
 * twice and counts every attempt as two; it is also pulled into every {@code @WebMvcTest} slice in the
 * codebase, which would put a rate limit in front of ten controller tests that have nothing to do with
 * authentication. An interceptor is registered once, explicitly, against the two paths it is for.
 *
 * <p>The address comes from {@code getRemoteAddr()} <em>after</em> Tomcat's RemoteIp valve has run, so
 * it is the address the trusted proxy reported and not whatever the client put in
 * {@code X-Forwarded-For}. That configuration is the load-bearing half of this: with the default
 * trusted-proxy range, a caller can name any address it likes and the limit counts a different bucket
 * every time. See {@code server.tomcat.remoteip.internal-proxies} in {@code application.yml}.
 *
 * <p>Nothing here logs the address or tags a metric with it — it is personal data (NFR-6) and an
 * unbounded tag (ADR-012). The counter is tagged by endpoint, which is a closed set of two.
 */
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    /** Counts refusals, so a credential-stuffing run is visible as a spike rather than as silence. */
    static final String REFUSALS = "auth.rate_limited";

    /** What an unrecognised target is counted as, so the tag can never be caller-supplied text. */
    static final String OTHER = "other";

    private final FixedWindowRateLimiter limiter;
    private final MeterRegistry meterRegistry;

    /**
     * The metric tag for a target, resolved against the closed set of limited paths.
     *
     * <p>Not {@code getRequestURI()} directly, which is the raw target the caller sent: matrix
     * parameters and percent-encoding survive it but not the pattern matching that got the request
     * here, so {@code /api/auth/login;x=1} is rate-limited as {@code /api/auth/login} and tagged as
     * itself. A refused caller could then mint a new Micrometer meter per attempt and grow the
     * registry — and the public {@code /actuator/prometheus} payload — without bound. ADR-012 forbids
     * an unbounded tag for exactly this reason; matching against the two known paths makes the tag
     * bounded by construction rather than by the caller's restraint.
     */
    private static String endpointTag(String requestUri) {
        for (String limited : RateLimitConfig.LIMITED_PATHS) {
            if (limited.equals(requestUri)) {
                return limited;
            }
        }
        return OTHER;
    }

    /**
     * {@inheritDoc}
     *
     * @throws TooManyRequestsException when the client has spent its allowance; the global handler maps
     *         it to 429 with {@code Retry-After}
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        FixedWindowRateLimiter.Decision decision =
                limiter.check(ClientAddressKey.of(request.getRemoteAddr()));
        if (decision.allowed()) {
            return true;
        }
        meterRegistry.counter(REFUSALS, "endpoint", endpointTag(request.getRequestURI())).increment();
        throw new TooManyRequestsException(decision.retryAfter());
    }
}
