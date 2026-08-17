package com.healthupgrades.architecture;

import com.tngtech.archunit.core.importer.ImportOption; // controls which classes are analysed
import com.tngtech.archunit.junit.AnalyzeClasses; // declares the packages ArchUnit imports for this test
import com.tngtech.archunit.junit.ArchTest; // marks a field as an executable architecture rule
import com.tngtech.archunit.lang.ArchRule; // the type of a single architecture rule
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition; // builds bounded-context slice rules

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue; // matches any origin class
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage; // single-package predicate
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage; // package predicate for ignores
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith; // class-name predicate
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes; // entry point for positive rules
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses; // entry point for forbidding rules

/**
 * Enforces the hexagonal (ports and adapters) architecture of the backend.
 *
 * <p>This is the "pragmatic" rule set: the domain is kept strictly framework-free (apart from the JPA
 * mapping annotations the entities carry), Spring Data is confined to the persistence adapters, outbound
 * ports are interfaces, and bounded contexts talk to each other only through shared surfaces (another
 * context's inbound ports, domain model, published web DTOs, or the common shared kernel). An application
 * service is allowed to use its own context's web DTOs.
 */
@AnalyzeClasses(packages = "com.healthupgrades", importOptions = ImportOption.DoNotIncludeTests.class) // analyse only production classes
class HexagonalArchitectureTest {

    /**
     * The domain must not depend on Spring, on adapters, or on the application layer. JPA
     * ({@code jakarta.persistence}) and Lombok are deliberately absent from the forbidden list, so the
     * entities may keep their persistence-mapping annotations.
     */
    @ArchTest
    static final ArchRule domain_is_free_of_framework_and_outer_layers =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "..adapter..", "..application..")
                    .as("the domain must not depend on Spring, adapters, or the application layer");

    /**
     * The application layer must not depend on outbound adapters — it drives them through outbound ports
     * (persistence, messaging, events) instead.
     */
    @ArchTest
    static final ArchRule application_does_not_depend_on_outbound_adapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                    .as("the application layer must not depend on outbound adapters");

    /**
     * Spring Data JPA may only be referenced from the persistence adapters, guaranteeing repositories are
     * hidden behind outbound ports everywhere else.
     */
    @ArchTest
    static final ArchRule spring_data_only_in_persistence_adapters =
            noClasses().that().resideOutsideOfPackage("..adapter.out.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.data.jpa..", "org.springframework.data.repository..")
                    .as("Spring Data JPA must be used only in persistence adapters");

    /** Outbound ports are contracts, so everything under {@code domain.port.out} must be an interface. */
    @ArchTest
    static final ArchRule outbound_ports_are_interfaces =
            classes().that().resideInAPackage("..domain.port.out..")
                    .should().beInterfaces()
                    .as("outbound ports must be interfaces");

    /**
     * Bounded contexts must not depend on each other's internals. Cross-context dependencies are allowed
     * only to shared surfaces: the common shared kernel, another context's domain model, its inbound
     * ports, or its published web DTOs.
     */
    @ArchTest
    static final ArchRule bounded_contexts_only_share_through_ports_and_published_models =
            SlicesRuleDefinition.slices()
                    .matching("com.healthupgrades.(*)..") // one slice per top-level package (context)
                    .namingSlices("$1")
                    .as("bounded contexts")
                    .should().notDependOnEachOther()
                    .ignoreDependency(alwaysTrue(), resideInAnyPackage(
                            "com.healthupgrades.common..", // shared kernel + cross-cutting adapters
                            "..domain.model..",            // another context's domain model is shareable
                            // A context's declared ports are its published surface whichever way they
                            // face. Inbound ports are the sanctioned way in; outbound ports are how a
                            // context states what it needs so a supplier can satisfy it without the
                            // consumer depending on the supplier (see UpgradeTrackingSummaryPort).
                            "..application.port..")
                            // Published presentation models only: web DTOs and sanctioned shareable web
                            // mappers — NOT controllers, request records, or other web wiring.
                            .or(resideInAPackage("..adapter.in.web..")
                                    .and(simpleNameEndingWith("Dto").or(simpleNameEndingWith("WebMapper")))));
}
