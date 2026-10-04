package com.healthupgrades.architecture;

import com.healthupgrades.common.time.ServerZone;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces that time is read through the injected clock, and that the scheduled jobs keep the zone it
 * reads (NFR-15, ADR-020).
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

    /**
     * Nothing outside an entity reads the time without the injected clock.
     *
     * <p>Every call site that stamps, announces or decides by the time has a test of its own, but a new
     * one calling {@code LocalDateTime.now()} would pass all of them. Entities are left out because their
     * {@code @PrePersist}/{@code @PreUpdate} hooks are #51's known deviation; the exclusion goes when #51
     * routes those through the clock.
     */
    @ArchTest
    static final ArchRule nothing_outside_an_entity_reads_the_time_without_the_clock =
            noClasses().that().areNotAnnotatedWith(Entity.class)
                    .should().callMethod(LocalDateTime.class, "now")
                    .orShould().callMethod(LocalDate.class, "now")
                    .orShould().callMethod(LocalTime.class, "now")
                    .orShould().callMethod(Instant.class, "now")
                    .as("nothing outside an entity may read the time without the injected clock");
}
