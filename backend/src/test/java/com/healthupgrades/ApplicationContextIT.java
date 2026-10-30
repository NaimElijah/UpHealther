package com.healthupgrades;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.tracking.domain.service.ProgressEvaluationService;
import com.healthupgrades.tracking.domain.service.StreakCalculator;
import com.healthupgrades.upgrade.domain.service.UpgradeSchedulingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

/**
 * Boots the whole application against a real PostgreSQL.
 *
 * <p>An integration test ({@code *IT}, run by Failsafe during {@code verify}) rather than a unit test,
 * so {@code mvn test} stays database-free as documented. The database is the throwaway one
 * {@link PostgresIT} starts.
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
 *
 * <p>It also pins one property of the context the shipped configuration builds: open-in-view is off, so a
 * request holds a pooled connection only while one of its transactions runs.
 */
@SpringBootTest
class ApplicationContextIT extends PostgresIT {

    @Autowired
    UpgradeSchedulingService upgradeSchedulingService;

    @Autowired
    StreakCalculator streakCalculator;

    @Autowired
    ProgressEvaluationService progressEvaluationService;

    @Autowired
    ApplicationContext context;

    @Test
    void GivenTheRealSchemaAndTheHandWiredBeans_WhenTheApplicationStarts_ThenItComesUpWithBothIntact() {
        // Reaching this point is the assertion. The context started, so Flyway migrated and Hibernate
        // validated the resulting schema; and the hand-wired beans are the fields above, which Spring
        // must inject before any test runs, so a missing @Bean fails the context rather than this body.
    }

    @Test
    void GivenTheShippedConfiguration_WhenTheApplicationStarts_ThenNoRequestKeepsAnEntityManagerOpen() {
        // Boot registers this interceptor exactly when spring.jpa.open-in-view is on, and while it is
        // registered every request holds its pooled connection until the response has been written.
        assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class))
                .isEmpty();
    }
}
