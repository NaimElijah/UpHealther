package com.healthupgrades.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.validation.FieldError;

/**
 * S5.A13: the session lifetimes are bounded when the application starts, so a setting that would turn
 * reuse detection off, or let a session live indefinitely, refuses the boot instead of running quietly.
 *
 * <p>Bound through Boot's own binder, as a deployment binds them: the bounds are constraints on the
 * record, and only binding runs them.
 */
class AuthSessionPropertiesTest {

    private final ApplicationContextRunner binding = new ApplicationContextRunner()
            .withUserConfiguration(BindsAuthSessionProperties.class)
            .withPropertyValues(
                    "app.auth.session.idle=7d", "app.auth.session.absolute=30d", "app.auth.session.rotation-grace=10s");

    @Test
    void GivenTheShippedDefaults_WhenTheyAreBound_ThenTheApplicationStarts() {
        binding.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void GivenNoRotationGraceAtAll_WhenItIsBound_ThenTheApplicationStarts() {
        // Zero forgives nothing: two tabs racing are then revoked as reuse. Strict, and an operator's
        // call to make; AuthSessionFlowIT runs that way.
        binding.withPropertyValues("app.auth.session.rotation-grace=0s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void GivenANegativeRotationGrace_WhenItIsBound_ThenTheApplicationRefusesToStartNamingIt() {
        // Below zero means nothing: no credential is ever spent before the instant it was spent.
        binding.withPropertyValues("app.auth.session.rotation-grace=-1s")
                .run(context -> assertRefusedFor(context.getStartupFailure(), "rotationGrace"));
    }

    @Test
    void GivenARotationGraceOfThirtyDays_WhenItIsBound_ThenTheApplicationRefusesToStartNamingIt() {
        // The finding's own scenario: a grace window as long as a session forgives every replay, which
        // turns reuse detection off.
        binding.withPropertyValues("app.auth.session.rotation-grace=30d")
                .run(context -> assertRefusedFor(context.getStartupFailure(), "rotationGrace"));
    }

    @Test
    void GivenAnAbsoluteCapBeyondItsBound_WhenItIsBound_ThenTheApplicationRefusesToStartNamingIt() {
        binding.withPropertyValues("app.auth.session.idle=7d", "app.auth.session.absolute=3650d")
                .run(context -> assertRefusedFor(context.getStartupFailure(), "absolute"));
    }

    @Test
    void GivenAnIdleWindowShorterThanAnAccessToken_WhenItIsBound_ThenTheApplicationRefusesToStartNamingIt() {
        binding.withPropertyValues("app.auth.session.idle=5m")
                .run(context -> assertRefusedFor(context.getStartupFailure(), "idle"));
    }

    @Test
    void GivenAnIdleWindowLongerThanTheAbsoluteCap_WhenItIsBound_ThenTheApplicationRefusesToStart() {
        // The idle expiry never passes the cap, so an idle window beyond it is a setting that does
        // nothing it says: almost certainly a mistake, and better named at boot.
        binding.withPropertyValues("app.auth.session.idle=40d", "app.auth.session.absolute=30d")
                .run(context -> assertRefusedFor(context.getStartupFailure(), "idleWithinAbsolute"));
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
    @EnableConfigurationProperties(AuthSessionProperties.class)
    static class BindsAuthSessionProperties {}
}
