package com.healthupgrades.architecture;

import com.healthupgrades.common.time.ServerZone;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.scheduling.annotation.Scheduled;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Enforces that the scheduled jobs and the clock they consult keep time in one zone (NFR-15, ADR-020).
 *
 * <p>Imports the same classes as {@link HexagonalArchitectureTest}, so ArchUnit's cache serves both.
 */
@AnalyzeClasses(packages = "com.healthupgrades", importOptions = ImportOption.DoNotIncludeTests.class)
class ServerTimeArchitectureTest {

    /**
     * Every {@code @Scheduled} job resolves its cron in {@link ServerZone}.
     *
     * <p>Spring resolves a cron in the host's zone unless the annotation names one, while the clock the
     * job then consults reads UTC. A job without the zone fires at an hour the rest of the server does
     * not agree on — on any developer machine outside UTC — and no other test would notice.
     */
    @ArchTest
    static final ArchRule every_scheduled_job_runs_in_the_server_zone =
            methods().that().areAnnotatedWith(Scheduled.class)
                    .should().beAnnotatedWith(DescribedPredicate.<JavaAnnotation<?>>describe(
                            "@Scheduled(zone = ServerZone.ID)",
                            annotation -> annotation.getRawType().isEquivalentTo(Scheduled.class)
                                    && ServerZone.ID.equals(annotation.get("zone").orElse(null))))
                    .as("every @Scheduled job must resolve its cron in ServerZone.ID, the zone the clock reads");
}
