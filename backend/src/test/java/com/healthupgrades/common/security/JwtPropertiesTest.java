package com.healthupgrades.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.validation.FieldError;

/**
 * S5.A13 for the access token: its lifetime is bounded when the application starts, so a setting that
 * makes a leaked token worth hours, or renews every tab every few seconds, refuses the boot.
 */
class JwtPropertiesTest {

    private final ApplicationContextRunner binding = new ApplicationContextRunner()
            .withUserConfiguration(BindsJwtProperties.class)
            .withPropertyValues(
                    "app.jwt.secret=only-bound-never-turned-into-a-key",
                    "app.jwt.access-token-ttl=15m",
                    "app.jwt.issuer=healthupgrades",
                    "app.jwt.audience=healthupgrades-web",
                    "app.jwt.clock-skew=30s");

    @Test
    void GivenTheShippedLifetime_WhenItIsBound_ThenTheApplicationStarts() {
        binding.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void GivenAnAccessTokenLifetimeOfADay_WhenItIsBound_ThenTheApplicationRefusesToStartNamingIt() {
        binding.withPropertyValues("app.jwt.access-token-ttl=1d")
                .run(context -> assertRefusedFor(context.getStartupFailure(), "accessTokenTtl"));
    }

    @Test
    void GivenAnAccessTokenLifetimeOfSeconds_WhenItIsBound_ThenTheApplicationRefusesToStartNamingIt() {
        binding.withPropertyValues("app.jwt.access-token-ttl=10s")
                .run(context -> assertRefusedFor(context.getStartupFailure(), "accessTokenTtl"));
    }

    private static void assertRefusedFor(Throwable startupFailure, String field) {
        assertThat(startupFailure).isNotNull();
        assertThat(NestedExceptionUtils.getMostSpecificCause(startupFailure))
                .isInstanceOfSatisfying(
                        BindValidationException.class,
                        failure -> assertThat(failure.getValidationErrors().getAllErrors())
                                .map(error -> ((FieldError) error).getField())
                                .containsExactly(field));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class BindsJwtProperties {}
}
