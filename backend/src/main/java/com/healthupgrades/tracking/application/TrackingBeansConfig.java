package com.healthupgrades.tracking.application;

import com.healthupgrades.tracking.domain.service.ProgressEvaluationService;
import com.healthupgrades.tracking.domain.service.StreakCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the tracking context's pure domain services as Spring beans.
 *
 * <p>Both services are framework-free (no {@code @Service}); the application layer exposes them so
 * {@code TrackingService} can have them injected. The dashboard injects neither: it reads streaks
 * through {@code StreakQuery}.
 */
@Configuration
public class TrackingBeansConfig {

    /** Exposes the stateless {@link StreakCalculator} as an injectable bean. */
    @Bean
    public StreakCalculator streakCalculator() {
        return new StreakCalculator();
    }

    /** Exposes the stateless {@link ProgressEvaluationService} as an injectable bean. */
    @Bean
    public ProgressEvaluationService progressEvaluationService() {
        return new ProgressEvaluationService();
    }
}
