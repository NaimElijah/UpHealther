package com.healthupgrades.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.healthupgrades.HealthUpgradesApplication;
import com.healthupgrades.common.time.ServerZone;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import java.time.Clock;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;

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
    static final ArchRule every_scheduled_job_runs_in_the_server_zone = methods()
            .that()
            .areAnnotatedWith(Scheduled.class)
            .should()
            .beAnnotatedWith(DescribedPredicate.<JavaAnnotation<?>>describe(
                    "@Scheduled(zone = ServerZone.ID)",
                    annotation -> annotation.getRawType().isEquivalentTo(Scheduled.class)
                            && ServerZone.ID.equals(annotation.get("zone").orElse(null))))
            .as("every @Scheduled job must resolve its cron in ServerZone.ID, the zone the clock reads");

    /**
     * Nothing outside an entity reads the time without the injected clock.
     *
     * <p>Every call site that stamps, announces or decides by the time has a test of its own, but a new
     * one reading the wall clock would pass all of them. So every {@code now} in {@code java.time} is
     * forbidden unless its one argument is a {@link Clock} — {@code ZonedDateTime.now()} and
     * {@code LocalDate.now(zone)} read the system clock as surely as {@code LocalDateTime.now()} does —
     * and so is {@code System.currentTimeMillis()}. Entities are left out because their
     * {@code @PrePersist}/{@code @PreUpdate} hooks are #51's known deviation; the exclusion goes when #51
     * routes those through the clock.
     */
    @ArchTest
    static final ArchRule nothing_outside_an_entity_reads_the_time_without_the_clock = noClasses()
            .that()
            .areNotAnnotatedWith(Entity.class)
            .should()
            .callMethodWhere(readsTheWallClock())
            .as("nothing outside an entity may read the time without the injected clock");

    /**
     * Only the application's {@code clock()} bean builds a clock from the system's.
     *
     * <p>{@code Clock.systemDefaultZone()} anywhere else would hand a caller a clock nobody injected and
     * in the host's zone, which is the two things NFR-15 and ADR-020 rule out at once.
     */
    @ArchTest
    static final ArchRule only_the_clock_bean_builds_a_system_clock = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(HealthUpgradesApplication.class.getName())
            .should()
            .callMethodWhere(DescribedPredicate.describe(
                    "Clock.system*()",
                    call -> call.getTargetOwner().isEquivalentTo(Clock.class)
                            && call.getName().startsWith("system")))
            .as("only HealthUpgradesApplication.clock() may build a clock from the system's");

    /** A {@code java.time} {@code now} that does not take a {@link Clock}, or {@code currentTimeMillis}. */
    private static DescribedPredicate<JavaMethodCall> readsTheWallClock() {
        return DescribedPredicate.describe(
                "a java.time now() without a Clock, or System.currentTimeMillis()",
                call -> (call.getTargetOwner().getPackageName().equals("java.time")
                                && call.getName().equals("now")
                                && !call.getTarget().getRawParameterTypes().stream()
                                        .map(JavaClass::getName)
                                        .toList()
                                        .equals(List.of(Clock.class.getName())))
                        || (call.getTargetOwner().isEquivalentTo(System.class)
                                && call.getName().equals("currentTimeMillis")));
    }
}
