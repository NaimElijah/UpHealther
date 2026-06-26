package com.healthupgrades;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HealthUpgradesApplication {
    public static void main(String[] args) {
        SpringApplication.run(HealthUpgradesApplication.class, args);
    }
}
