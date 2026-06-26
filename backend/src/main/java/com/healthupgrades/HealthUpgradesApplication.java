package com.healthupgrades;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class HealthUpgradesApplication {
    public static void main(String[] args) {
        SpringApplication.run(HealthUpgradesApplication.class, args);
    }

    /** Injectable clock so time-based logic (schedulers) is timezone-explicit and testable. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
