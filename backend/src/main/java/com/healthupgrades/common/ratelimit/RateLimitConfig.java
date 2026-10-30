package com.healthupgrades.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the two rate limits against the endpoints they are for, and nothing else.
 *
 * <p>The paths are listed here rather than checked inside the interceptor, so the set of rate-limited
 * endpoints is one readable list instead of a condition buried in a request path. All four are
 * anonymous: everything else in the API needs a token, which is its own limit on how fast an attacker
 * can try. Each pair has its own limiter, from {@link RateLimiters}, so a busy tab's renewals never
 * spend anybody's sign-in attempts, and a mistyped password never costs a renewal.
 *
 * <p>Registration lives in {@code common} beside the interceptor rather than in {@code auth}, because a
 * {@code WebMvcConfigurer} is cross-cutting web wiring and putting it in a bounded context would give
 * that context a say over the whole dispatcher.
 */
@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, SessionRateLimitProperties.class})
public class RateLimitConfig implements WebMvcConfigurer {

    /**
     * The endpoints a client may reach without a credential, and therefore the ones worth guessing at.
     *
     * <p>Sign-in is the obvious one. Registration is here too, and for a different reason: it is not
     * guessable, but it is free account creation, and an unlimited one is how a database fills with
     * junk faster than anybody notices.
     */
    static final List<String> SIGN_IN_PATHS = List.of("/api/auth/login", "/api/auth/register");

    /**
     * The endpoints that act on the refresh cookie alone. Neither takes a password, but each attempt
     * reads a session under a lock and writes an audit entry, so an unlimited caller could write the
     * audit stream at will (S5.A11). Their limit is looser, because a signed-in browser calls them on
     * its own.
     */
    static final List<String> SESSION_PATHS = List.of("/api/auth/refresh", "/api/auth/logout");

    private final FixedWindowRateLimiter signInLimiter;
    private final FixedWindowRateLimiter sessionLimiter;
    private final MeterRegistry meterRegistry;

    /**
     * Takes the two limiters by name, because they are the same type.
     *
     * @param signInLimiter  the limiter for {@link #SIGN_IN_PATHS}
     * @param sessionLimiter the limiter for {@link #SESSION_PATHS}
     * @param meterRegistry  where the refusals are counted
     */
    public RateLimitConfig(
            @Qualifier(RateLimiters.SIGN_IN) FixedWindowRateLimiter signInLimiter,
            @Qualifier(RateLimiters.SESSION) FixedWindowRateLimiter sessionLimiter,
            MeterRegistry meterRegistry) {
        this.signInLimiter = signInLimiter;
        this.sessionLimiter = sessionLimiter;
        this.meterRegistry = meterRegistry;
    }

    /** {@inheritDoc} */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(signInLimiter, meterRegistry, SIGN_IN_PATHS))
                .addPathPatterns(SIGN_IN_PATHS);
        registry.addInterceptor(new RateLimitInterceptor(sessionLimiter, meterRegistry, SESSION_PATHS))
                .addPathPatterns(SESSION_PATHS);
    }
}
