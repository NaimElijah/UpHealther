package com.healthupgrades;

import com.healthupgrades.tracking.domain.service.ProgressEvaluationService;
import com.healthupgrades.tracking.domain.service.StreakCalculator;
import com.healthupgrades.upgrade.domain.service.UpgradeSchedulingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application against a real PostgreSQL.
 *
 * <p>An integration test ({@code *IT}, run by Failsafe during {@code verify}) rather than a unit test,
 * so {@code mvn test} stays database-free as documented.
 *
 * <p>It exists because nothing previously verified that the application starts at all. Two things could
 * break with a green build and only fail on deploy:
 *
 * <ul>
 *   <li><strong>Bean wiring.</strong> The pure domain services carry no stereotype annotation and are
 *       registered by hand in {@code UpgradeBeansConfig} and {@code TrackingBeansConfig}. Dropping a
 *       {@code @Bean} there compiles and passes every unit test, because those tests construct the
 *       services directly.</li>
 *   <li><strong>Schema drift.</strong> Flyway owns the schema and Hibernate runs with
 *       {@code ddl-auto: validate}, so an entity change without a matching migration fails at startup.
 *       Booting here is what turns that into a build failure.</li>
 * </ul>
 */
@SpringBootTest
class ApplicationContextIT {

    @Autowired UpgradeSchedulingService upgradeSchedulingService;
    @Autowired StreakCalculator streakCalculator;
    @Autowired ProgressEvaluationService progressEvaluationService;

    @Test
    void applicationStartsWithTheSchemaItExpectsAndTheDomainServicesWiredByHand() {
        // Reaching this point already proves the context started, which means Flyway migrated and
        // Hibernate validated the resulting schema. The assertions pin the hand-wired beans specifically.
        assertThat(upgradeSchedulingService).isNotNull();
        assertThat(streakCalculator).isNotNull();
        assertThat(progressEvaluationService).isNotNull();
    }
}
