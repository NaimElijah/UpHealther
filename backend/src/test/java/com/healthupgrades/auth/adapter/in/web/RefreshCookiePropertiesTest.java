package com.healthupgrades.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.validation.FieldError;

/**
 * The refresh cookie's name and path are checked together when the application starts (NFR-36, S5.A8).
 *
 * <p>A browser refuses a {@code __Host-} cookie set on any path but {@code /}, and says nothing to the
 * server about it. A deployment that narrowed the path back to {@code /api/auth}, as ADR-015 once had it,
 * would leave every browser without a session while every request still answered; so the pair refuses
 * the boot instead. Bound through Boot's own binder, as a deployment binds them.
 */
class RefreshCookiePropertiesTest {

    private final ApplicationContextRunner binding =
            new ApplicationContextRunner().withUserConfiguration(BindsRefreshCookieProperties.class);

    @ParameterizedTest(name = "a cookie named {0} on {1} starts: {2}")
    @CsvSource({
        "__Host-refresh_token, /, true",
        "__Host-refresh_token, /api/auth, false",
        "__Host-refresh_token, /api, false",
        "refresh_token, /api/auth, true"
    })
    void GivenACookieNameAndPath_WhenTheyAreBound_ThenAHostPrefixedNameStartsOnlyOnTheRootPath(
            String name, String path, boolean starts) {
        binding.withPropertyValues("app.auth.cookie.name=" + name, "app.auth.cookie.path=" + path)
                .run(context -> {
                    if (starts) {
                        assertThat(context).hasNotFailed();
                    } else {
                        assertRefusedFor(context.getStartupFailure(), "pathAllowedByName");
                    }
                });
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
    @EnableConfigurationProperties(RefreshCookieProperties.class)
    static class BindsRefreshCookieProperties {}
}
