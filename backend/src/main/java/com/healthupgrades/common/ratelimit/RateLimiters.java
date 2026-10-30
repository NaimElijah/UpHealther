package com.healthupgrades.common.ratelimit;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two rate limiters, each with its own map, so spending one allowance never spends the other.
 *
 * <p>Sign-in and registration share one; refresh and sign-out share the other (S5.A11). Kept apart from
 * {@link RateLimitConfig}, which registers them, because a {@code WebMvcConfigurer} that injected beans
 * it also declared would depend on itself. A web slice supplies both under the same names from
 * {@code WebSliceSupport}, with a clock that stands still.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiters {

    /** The bean name of the limiter in front of sign-in and registration. */
    public static final String SIGN_IN = "signInRateLimiter";

    /** The bean name of the limiter in front of refresh and sign-out. */
    public static final String SESSION = "sessionRateLimiter";

    /**
     * The limiter for the two endpoints that take a password.
     *
     * @param properties the sign-in limits, from {@code app.rate-limit}
     * @param clock      the application clock
     * @return a limiter with a map of its own
     */
    @Bean(SIGN_IN)
    FixedWindowRateLimiter signInRateLimiter(RateLimitProperties properties, Clock clock) {
        return new FixedWindowRateLimiter(properties, clock);
    }

    /**
     * The limiter for the two endpoints that act on the refresh cookie alone.
     *
     * @param properties the session limits, from {@code app.session-rate-limit}
     * @param clock      the application clock
     * @return a limiter with a map of its own
     */
    @Bean(SESSION)
    FixedWindowRateLimiter sessionRateLimiter(SessionRateLimitProperties properties, Clock clock) {
        return new FixedWindowRateLimiter(properties.limits(), clock);
    }
}
