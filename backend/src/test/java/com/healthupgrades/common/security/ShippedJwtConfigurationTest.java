package com.healthupgrades.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.validation.FieldError;

/**
 * Pins what the jar's own {@code application.yml} does about the signing key (NFR-3): it has none, so a
 * deployment that forgets {@code JWT_SECRET} refuses to start instead of signing every token with a key
 * anyone can read in the repository (#87, ADR-042).
 *
 * <p>The configuration is read the way a deployment reads it. {@code spring.config.location} names the
 * shipped file alone, so the development key in {@code backend/config/} and the test key on the test
 * classpath are both out of reach. The process environment and system properties are removed before the
 * file is read, so {@code JWT_SECRET} is genuinely absent unless a test supplies it, and whatever the
 * shell running the build exports cannot decide the outcome. Setting it to an empty string instead would
 * not do: an empty variable counts as present, which skips a fallback just as a real value would.
 */
class ShippedJwtConfigurationTest {

    /** Only bound, never turned into a key, so its strength does not matter here. */
    private static final String SUPPLIED_SECRET = "a-secret-the-deployment-supplied";

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withUserConfiguration(BindsJwtProperties.class);

    @Test
    void GivenTheShippedConfigurationAndNoJwtSecret_WhenTheApplicationStarts_ThenItRefusesToStart() {
        shipped.run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .isInstanceOfSatisfying(BindValidationException.class, failure -> {
                        assertThat(failure.getValidationErrors().getName()).hasToString("app.jwt");
                        assertThat(failure.getValidationErrors().getAllErrors())
                                .map(error -> ((FieldError) error).getField())
                                .containsExactly("secret");
                    });
        });
    }

    @Test
    void GivenTheShippedConfigurationAndAJwtSecret_WhenTheApplicationStarts_ThenItBindsThatSecret() {
        shipped.withPropertyValues("JWT_SECRET=" + SUPPLIED_SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JwtProperties.class).secret()).isEqualTo(SUPPLIED_SECRET);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class BindsJwtProperties {}
}
