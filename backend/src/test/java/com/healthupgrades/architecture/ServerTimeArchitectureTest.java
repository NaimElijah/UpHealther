package com.healthupgrades.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.healthupgrades.HealthUpgradesApplication;
import com.healthupgrades.common.time.ServerZone;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Clock;
import java.time.InstantSource;
import java.util.List;
import java.util.function.Predicate;
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
     * Nothing outside an entity's lifecycle hook reads the time without the injected clock.
     *
     * <p>Every call site that stamps, announces or decides by the time has a test of its own, but a new
     * one reading the wall clock would pass all of them. So every {@code now} in {@code java.time} and
     * its subpackages is forbidden unless its one argument is a {@link Clock} — {@code ZonedDateTime.now()}
     * and {@code LocalDate.now(zone)} read the system clock as surely as {@code LocalDateTime.now()}
     * does — and so is {@code System.currentTimeMillis()}, whether called or passed as a method
     * reference. Only {@code @PrePersist} and {@code @PreUpdate} methods are left out: their timestamps
     * are #51's known deviation, and the exemption goes when #51 routes them through the clock. The rest
     * of an entity is not, because under ADR-001 the entity is the domain model, where due and overdue
     * are decided.
     */
    @ArchTest
    static final ArchRule nothing_outside_a_lifecycle_hook_reads_the_time_without_the_clock = noClasses()
            .should()
            .accessTargetWhere(readsTheWallClock()
                    .and(not(originatesIn(
                            "a @PrePersist or @PreUpdate method",
                            origin -> origin.isAnnotatedWith(PrePersist.class)
                                    || origin.isAnnotatedWith(PreUpdate.class)))))
            .as("nothing outside a @PrePersist or @PreUpdate method may read the time without the injected clock");

    /**
     * Only the application's {@code clock()} bean builds a clock from the system's.
     *
     * <p>{@code Clock.systemDefaultZone()} or {@code InstantSource.system()} anywhere else would hand a
     * caller a clock nobody injected, the first in the host's zone, which is what NFR-15 and ADR-020 rule
     * out. The exemption is that one method, not its class, so {@code main} gets none.
     */
    @ArchTest
    static final ArchRule only_the_clock_bean_builds_a_system_clock = noClasses()
            .should()
            .accessTargetWhere(buildsASystemClock()
                    .and(not(originatesIn(
                            "HealthUpgradesApplication.clock()",
                            origin -> origin.getOwner().isEquivalentTo(HealthUpgradesApplication.class)
                                    && origin.getName().equals("clock")
                                    && origin.getRawParameterTypes().isEmpty()))))
            .as("only HealthUpgradesApplication.clock() may build a clock from the system's");

    /**
     * A {@code java.time} {@code now} that does not take a {@link Clock}, or {@code currentTimeMillis},
     * called or referenced.
     */
    private static DescribedPredicate<JavaAccess<?>> readsTheWallClock() {
        return targets(
                "a java.time now() without a Clock, or System.currentTimeMillis()",
                target -> (target.getOwner().getPackageName().startsWith("java.time")
                                && target.getName().equals("now")
                                && !target.getRawParameterTypes().stream()
                                        .map(JavaClass::getName)
                                        .toList()
                                        .equals(List.of(Clock.class.getName())))
                        || (target.getOwner().isEquivalentTo(System.class)
                                && target.getName().equals("currentTimeMillis")));
    }

    /** {@code Clock.system*()} or {@code InstantSource.system()}, called or referenced. */
    private static DescribedPredicate<JavaAccess<?>> buildsASystemClock() {
        return targets(
                "Clock.system*() or InstantSource.system()",
                target -> (target.getOwner().isEquivalentTo(Clock.class)
                                || target.getOwner().isEquivalentTo(InstantSource.class))
                        && target.getName().startsWith("system"));
    }

    /**
     * An access to a method or constructor the predicate accepts. A method reference such as
     * {@code LocalDate::now} is an access but not a call, so {@code callMethodWhere} would miss it.
     */
    private static DescribedPredicate<JavaAccess<?>> targets(
            String description, Predicate<CodeUnitAccessTarget> target) {
        return DescribedPredicate.describe(
                description,
                access -> access.getTarget() instanceof CodeUnitAccessTarget codeUnit && target.test(codeUnit));
    }

    /** An access made from a method or constructor the predicate accepts. */
    private static DescribedPredicate<JavaAccess<?>> originatesIn(String description, Predicate<JavaCodeUnit> origin) {
        return DescribedPredicate.describe("made from " + description, access -> origin.test(access.getOrigin()));
    }
}
