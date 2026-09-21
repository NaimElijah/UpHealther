package com.healthupgrades.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the rate limit against the two endpoints it is for, and nothing else.
 *
 * <p>The paths are listed here rather than checked inside the interceptor, so the set of rate-limited
 * endpoints is one readable list instead of a condition buried in a request path. Both are anonymous:
 * everything else in the API needs a token, which is its own limit on how fast an attacker can try.
 *
 * <p>Registration lives in {@code common} beside the interceptor rather than in {@code auth}, because a
 * {@code WebMvcConfigurer} is cross-cutting web wiring and putting it in a bounded context would give
 * that context a say over the whole dispatcher.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

    /**
     * The endpoints a client may reach without a credential, and therefore the ones worth guessing at.
     *
     * <p>Sign-in is the obvious one. Registration is here too, and for a different reason: it is not
     * guessable, but it is free account creation, and an unlimited one is how a database fills with
     * junk faster than anybody notices.
     */
    static final String[] LIMITED_PATHS = {"/api/auth/login", "/api/auth/register"};

    private final FixedWindowRateLimiter limiter;
    private final MeterRegistry meterRegistry;

    /** {@inheritDoc} */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiter, meterRegistry))
                .addPathPatterns(LIMITED_PATHS);
    }
}
