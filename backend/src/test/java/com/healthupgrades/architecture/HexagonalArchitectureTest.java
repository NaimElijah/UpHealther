package com.healthupgrades.architecture;

import com.tngtech.archunit.core.importer.ImportOption; // controls which classes are analysed
import com.tngtech.archunit.junit.AnalyzeClasses; // declares the packages ArchUnit imports for this test
import com.tngtech.archunit.junit.ArchTest; // marks a field as an executable architecture rule
import com.tngtech.archunit.lang.ArchRule; // the type of a single architecture rule

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes; // entry point for building rules

/**
 * Verifies the hexagonal (ports and adapters) architecture constraints of the backend.
 *
 * <p>The full rule set (domain purity with a JPA carve-out, application/adapter separation,
 * Spring Data confinement, outbound ports as interfaces, and bounded-context isolation) is
 * introduced incrementally as the migration progresses and fully enforced in the final phase.
 * Until then this class holds a single baseline sanity rule so the test suite stays green.
 */
@AnalyzeClasses(packages = "com.healthupgrades", importOptions = ImportOption.DoNotIncludeTests.class) // analyse only production classes under the root package
class HexagonalArchitectureTest {

    /** Baseline sanity check: every analysed production class lives under the project root package. */
    @ArchTest // executed by the ArchUnit JUnit 5 runner
    static final ArchRule all_production_code_lives_under_the_root_package =
            classes().should().resideInAPackage("com.healthupgrades.."); // trivially true today; confirms ArchUnit is wired
}
