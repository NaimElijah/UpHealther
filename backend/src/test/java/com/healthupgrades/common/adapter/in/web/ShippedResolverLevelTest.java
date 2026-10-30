package com.healthupgrades.common.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

/**
 * NFR-6: the jar's own {@code application.yml} pins Spring's {@link ExceptionHandlerExceptionResolver} at
 * INFO, so turning {@code org.springframework.web} up to DEBUG to diagnose a request cannot write a refused
 * registration's password.
 *
 * <p>The resolver logs {@code Resolved [<exception>]} at DEBUG, untruncated, under its own class name, and a
 * {@code MethodArgumentNotValidException}'s message quotes every rejected value. {@link
 * GlobalExceptionHandler} logs a refused request by its exception's type for the same reason, and that is
 * no use if the framework's own line beside it quotes the body.
 *
 * <p>Read as {@code ShippedAuditLevelTest} reads the shipped file: that file alone, with the process
 * environment removed, and the levels bound as Boot's logging system binds them.
 */
class ShippedResolverLevelTest {

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "spring.config.location=classpath:/application.yml", "logging.level.org.springframework.web=DEBUG");

    @Test
    void GivenSpringWebTurnedUpToDebug_WhenTheShippedLevelsAreBound_ThenTheExceptionResolverStaysAtInfo() {
        shipped.run(context -> {
            Map<String, LogLevel> levels = Binder.get(context.getEnvironment())
                    .bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                    .orElse(Map.of());

            assertThat(levels)
                    .containsEntry("org.springframework.web", LogLevel.DEBUG)
                    .containsEntry(ExceptionHandlerExceptionResolver.class.getName(), LogLevel.INFO);
        });
    }
}
