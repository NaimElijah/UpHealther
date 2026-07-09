package com.healthupgrades.tracking.application;

import com.healthupgrades.tracking.domain.service.ProgressEvaluationService; // pure domain service being exposed
import com.healthupgrades.tracking.domain.service.StreakCalculator; // pure domain service being exposed
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the tracking context's pure domain services as Spring beans.
 *
 * <p>Both services are framework-free (no {@code @Service}); the application layer exposes them so the
 * tracking and dashboard services can have them injected.
 */
@Configuration
public class TrackingBeansConfig {

    /** Exposes the stateless {@link StreakCalculator} as an injectable bean. */
    @Bean
    public StreakCalculator streakCalculator() {
        return new StreakCalculator(); // no dependencies -> plain construction
    }

    /** Exposes the stateless {@link ProgressEvaluationService} as an injectable bean. */
    @Bean
    public ProgressEvaluationService progressEvaluationService() {
        return new ProgressEvaluationService(); // no dependencies -> plain construction
    }
}
