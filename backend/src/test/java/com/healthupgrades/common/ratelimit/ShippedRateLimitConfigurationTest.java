package com.healthupgrades.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * The two limiters as {@link RateLimiters} builds them from the jar's own {@code application.yml}
 * (NFR-59, S5.A11).
 *
 * <p>The web slices build both limiters by hand in {@code WebSliceSupport}, and every IT raises the
 * limits out of the way, so without this nothing would see the session limiter wired from sign-in's
 * settings, one limiter registered under both names, or a shipped limit of six thousand. Read as
 * {@code ShippedJwtConfigurationTest} reads the shipped file: that file alone, with the process
 * environment removed. The clock stands still, so every attempt lands in one window.
 */
class ShippedRateLimitConfigurationTest {

    /** What the shipped file allows one address a minute on sign-in and registration together. */
    private static final int SHIPPED_SIGN_IN_LIMIT = 10;

    /** What it allows one address a minute on refresh and sign-out together. */
    private static final int SHIPPED_SESSION_LIMIT = 60;

    private static final String CLIENT = "203.0.113.40";

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withUserConfiguration(ShippedLimiters.class);

    @Test
    void GivenTheShippedConfiguration_WhenTheLimitersAreBuilt_ThenEachIsALimiterOfItsOwn() {
        shipped.run(context -> assertThat(context.getBean(RateLimiters.SESSION, FixedWindowRateLimiter.class))
                .isNotSameAs(context.getBean(RateLimiters.SIGN_IN, FixedWindowRateLimiter.class)));
    }

    @Test
    void GivenTheShippedConfiguration_WhenOneAddressRefreshesSixtyOneTimesInAMinute_ThenOnlyTheLastIsRefused() {
        shipped.run(context -> {
            FixedWindowRateLimiter sessions = context.getBean(RateLimiters.SESSION, FixedWindowRateLimiter.class);

            assertThat(attemptsAllowed(sessions, SHIPPED_SESSION_LIMIT)).isEqualTo(SHIPPED_SESSION_LIMIT);
            assertThat(sessions.check(CLIENT).allowed()).isFalse();
        });
    }

    @Test
    void GivenTheShippedConfiguration_WhenOneAddressSignsInElevenTimesInAMinute_ThenOnlyTheLastIsRefused() {
        shipped.run(context -> {
            FixedWindowRateLimiter signIns = context.getBean(RateLimiters.SIGN_IN, FixedWindowRateLimiter.class);

            assertThat(attemptsAllowed(signIns, SHIPPED_SIGN_IN_LIMIT)).isEqualTo(SHIPPED_SIGN_IN_LIMIT);
            assertThat(signIns.check(CLIENT).allowed()).isFalse();
        });
    }

    private static long attemptsAllowed(FixedWindowRateLimiter limiter, int attempts) {
        return IntStream.range(0, attempts)
                .filter(attempt -> limiter.check(CLIENT).allowed())
                .count();
    }

    /** The shipped limiters, with the properties they bind and a clock that stands still. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({RateLimitProperties.class, SessionRateLimitProperties.class})
    @Import(RateLimiters.class)
    static class ShippedLimiters {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-30T10:15:00Z"), ZoneOffset.UTC);
        }
    }
}
