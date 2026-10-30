package com.healthupgrades.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    /**
     * Every bound at its edge, on both sides, so moving a limit by a step or turning an inclusive bound
     * exclusive fails here rather than only a setting far outside it. A blank field means the settings
     * start; otherwise it is the property the refusal must name, among any others it names.
     */
    @ParameterizedTest(name = "idle {0}, absolute {1}, grace {2}: refused for [{3}]")
    @CsvSource({
        "1h, 1h, 0s, ''",
        "30d, 30d, 1m, ''",
        "7d, 90d, 10s, ''",
        "59m, 30d, 10s, idle",
        "1h, 59m, 10s, absolute",
        "7d, 91d, 10s, absolute",
        "7d, 30d, 61s, rotationGrace",
        "2592001s, 30d, 10s, idleWithinAbsolute"
    })
    void GivenSettingsOnEitherSideOfABound_WhenTheyAreBound_ThenOnlyThoseInsideItStart(
            String idle, String absolute, String grace, String refusedField) {
        binding.withPropertyValues(
                        "app.auth.session.idle=" + idle,
                        "app.auth.session.absolute=" + absolute,
                        "app.auth.session.rotation-grace=" + grace)
                .run(context -> {
                    if (refusedField.isEmpty()) {
                        assertThat(context).hasNotFailed();
                    } else {
                        assertThat(refusedFields(context.getStartupFailure())).contains(refusedField);
                    }
                });
    }

    private static List<String> refusedFields(Throwable startupFailure) {
        assertThat(startupFailure).isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(startupFailure);
        assertThat(cause).isInstanceOf(BindValidationException.class);
        return ((BindValidationException) cause)
                .getValidationErrors().getAllErrors().stream()
                        .map(error -> ((FieldError) error).getField())
                        .toList();
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
