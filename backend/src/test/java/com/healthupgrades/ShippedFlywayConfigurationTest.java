package com.healthupgrades;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * Pins where the jar's own {@code application.yml} tells Flyway to look: the migrations, and nothing
 * else (#84).
 *
 * <p>A Flyway callback location adds scripts that run after every migration. The demo account, with a
 * password published in this repository, is created by one, and only a development run may name it. So a
 * deployment, which reads nothing but the jar's file and its own environment, must find no callback
 * location there, or every database it migrates would hold an account anyone can sign in to.
 *
 * <p>The configuration is read as a deployment reads it, the way {@code ShippedJwtConfigurationTest} reads
 * it: {@code spring.config.location} names the shipped file alone, so {@code backend/config/}, which names
 * the demo location for a run from a checkout, is out of reach, and the process environment and system
 * properties are removed, so whatever the shell running the build exports cannot decide the outcome.
 */
class ShippedFlywayConfigurationTest {

    /** Boot's default migration location, which the shipped file leaves as it is. */
    private static final String MIGRATIONS = "classpath:db/migration";

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withUserConfiguration(BindsFlywayProperties.class);

    @Test
    void GivenTheShippedConfiguration_WhenFlywayIsConfigured_ThenItMigratesFromTheMigrationDirectoryAlone() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FlywayProperties.class).getLocations()).containsExactly(MIGRATIONS);
        });
    }

    @Test
    void GivenTheShippedConfiguration_WhenFlywayIsConfigured_ThenItNamesNoCallbackLocation() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FlywayProperties.class).getCallbackLocations())
                    .as("a callback location in the jar's configuration runs its scripts in every deployment,"
                            + " the demo account's included")
                    .isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlywayProperties.class)
    static class BindsFlywayProperties {}
}
