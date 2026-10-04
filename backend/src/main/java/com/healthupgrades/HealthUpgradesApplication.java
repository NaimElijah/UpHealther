package com.healthupgrades;

import com.healthupgrades.common.time.ServerZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Spring Boot entry point for the HealthUpgrades API.
 *
 * <p>Scheduling is enabled here because two driving adapters are scheduled jobs rather than HTTP
 * endpoints: {@code UpgradeOverdueScheduler} and {@code NotificationScheduler}.
 */
@SpringBootApplication
@EnableScheduling
public class HealthUpgradesApplication {

    /**
     * Boots the application context.
     *
     * @param args standard Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(HealthUpgradesApplication.class, args);
    }

    /**
     * The clock every time-dependent decision reads, so each one is testable and its zone explicit
     * (NFR-15): {@link ServerZone}, never the host's (ADR-020).
     *
     * @return the system clock in the server zone
     */
    @Bean
    public Clock clock() {
        return Clock.system(ServerZone.ZONE);
    }
}
