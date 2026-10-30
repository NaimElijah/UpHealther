package com.healthupgrades.common.adapter.out.audit;

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

/**
 * S5.C9: the jar's own {@code application.yml} pins the {@code AUDIT} logger at INFO, so turning the root
 * level down to quiet a deployment cannot erase the audit trail while {@code audit.events} goes on
 * counting the entries nobody wrote.
 *
 * <p>Read as {@code ShippedJwtConfigurationTest} reads the shipped file: that file alone, with the process
 * environment removed. The levels are bound the way Boot's logging system binds them, as a map from
 * logger name to level, which is what decides whether the key reaches the {@code AUDIT} logger at all.
 */
class ShippedAuditLevelTest {

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml", "logging.level.root=WARN");

    @Test
    void GivenTheRootLevelTurnedDownToWarn_WhenTheShippedLevelsAreBound_ThenTheAuditLoggerStaysAtInfo() {
        shipped.run(context -> {
            Map<String, LogLevel> levels = Binder.get(context.getEnvironment())
                    .bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                    .orElse(Map.of());

            assertThat(levels)
                    .containsEntry("root", LogLevel.WARN)
                    .containsEntry(LoggingAuditTrail.LOGGER_NAME, LogLevel.INFO);
        });
    }
}
