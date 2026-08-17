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
 * context's ports, domain model, published domain events, published web DTOs, or the common shared
 * kernel).
 */
@AnalyzeClasses(packages = "com.healthupgrades", importOptions = ImportOption.DoNotIncludeTests.class) // analyse only production classes
class HexagonalArchitectureTest {

    /**
     * The domain must not depend on frameworks, on adapters, or on the application layer.
     *
     * <p>JPA ({@code jakarta.persistence}) and Lombok are deliberately absent from the forbidden list, so
     * the entities may keep their persistence-mapping annotations — the one compromise ADR-001 accepts.
     * Everything else is listed explicitly rather than relying on the Spring ban alone: without them a
     * domain class could pick up Jackson serialisation, bean-validation constraints or servlet types and
     * nothing would notice.
     */
    @ArchTest
    static final ArchRule domain_is_free_of_framework_and_outer_layers =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.validation..",
                            "jakarta.servlet..",
                            "com.fasterxml.jackson..",
                            "org.hibernate..",
                            "..adapter..", "..application..")
                    .as("the domain must not depend on frameworks, adapters, or the application layer");

    /**
     * The application layer must not depend on adapters in either direction. It drives the outbound side
     * through ports (persistence, messaging, events), and states its own input and output shapes —
     * command and result records under {@code application.port.in} — rather than accepting or returning
     * the web adapter's request and response records.
     */
    @ArchTest
    static final ArchRule application_does_not_depend_on_adapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter..")
                    .as("the application layer must not depend on adapters");

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

    /**
     * Outbound ports are contracts, so anything named {@code *Port} must be an interface — in
     * {@code domain.port.out} and in {@code application.port.out} alike.
     *
     * <p>Matched by name rather than by package because a port package also holds the records its
     * methods exchange: {@code UpgradeTrackingSummaryPort} sits beside {@code UpgradeTrackingSummary}.
     * Scoping this rule to {@code domain.port.out} left the port the cycle-break rests on unguarded.
     */
    @ArchTest
    static final ArchRule outbound_ports_are_interfaces =
            classes().that().resideInAnyPackage("..domain.port.out..", "..application.port.out..")
                    .and().haveSimpleNameEndingWith("Port")
                    .should().beInterfaces()
                    .as("outbound ports must be interfaces");

    /** Ports belong in a port package, so the rules that constrain them cannot be sidestepped. */
    @ArchTest
    static final ArchRule ports_live_in_a_port_package =
            classes().that().haveSimpleNameEndingWith("Port")
                    .should().resideInAnyPackage("..domain.port.out..", "..application.port.out..")
                    .as("classes named *Port must live in a port package");

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
                            "..domain.event..",            // published domain events are published language
                            // A context's declared ports are its published surface whichever way they
                            // face. Inbound ports are the sanctioned way in; outbound ports are how a
                            // context states what it needs so a supplier can satisfy it without the
                            // consumer depending on the supplier (see UpgradeTrackingSummaryPort).
                            "..application.port..")
                            // Published presentation models only: web DTOs and sanctioned shareable web
                            // mappers — NOT controllers, request records, or other web wiring.
                            .or(resideInAPackage("..adapter.in.web..")
                                    .and(simpleNameEndingWith("Dto").or(simpleNameEndingWith("WebMapper")))));

    /**
     * Bounded contexts must form an acyclic graph.
     *
     * <p>Distinct from the rule above, which constrains <em>what</em> may be shared: a cycle can form
     * entirely out of sanctioned surfaces, and did — {@code upgrade} and {@code tracking} depended on
     * each other through nothing but inbound ports, domain models and published DTOs, so every edge was
     * individually allowed while the pair as a whole could not be reasoned about or extracted
     * separately. Nothing here is ignored, because a cycle routed through a shared surface is still a
     * cycle.
     */
    @ArchTest
    static final ArchRule bounded_contexts_are_free_of_cycles =
            SlicesRuleDefinition.slices()
                    .matching("com.healthupgrades.(*)..")
                    .namingSlices("$1")
                    .as("bounded contexts")
                    .should().beFreeOfCycles();

    /**
     * A driving adapter must not reach a driven one directly — it goes through the application layer and
     * its ports. Without this, a controller could call a {@code *RepositoryAdapter} and bypass the port
     * boundary entirely, which the Spring Data rule would not catch because the adapter class is not
     * itself a Spring Data type.
     *
     * <p>The reverse direction is deliberately not forbidden: the STOMP push adapter renders
     * notifications with the same mapper the REST transport uses, because the two payloads must stay
     * identical and previously drifted as two copies.
     */
    @ArchTest
    static final ArchRule inbound_adapters_do_not_depend_on_outbound_adapters =
            noClasses().that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                    .as("inbound adapters must not depend on outbound adapters");

    /**
     * Use-case and query ports are contracts, so they must be interfaces. Command and result records
     * live in the same package by design and are matched by name rather than by package.
     */
    @ArchTest
    static final ArchRule inbound_ports_are_interfaces =
            classes().that().resideInAPackage("..application.port.in..")
                    .and().haveSimpleNameEndingWith("Query")
                    .or(resideInAPackage("..application.port.in..")
                            .and(simpleNameEndingWith("Command")))
                    .should().beInterfaces()
                    .as("inbound query and command ports must be interfaces");

    /** Controllers are a web concern and belong only in the web adapter. */
    @ArchTest
    static final ArchRule controllers_live_only_in_the_web_adapter =
            classes().that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .or().areAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
                    .or().areAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                    .should().resideInAPackage("..adapter.in.web..")
                    .as("controllers must live in the web adapter");

    /**
     * Spring Data repositories stay package-private, so reaching past a port into another context's
     * persistence is impossible by compilation rather than by discipline.
     */
    @ArchTest
    static final ArchRule spring_data_repositories_are_package_private =
            classes().that().haveSimpleNameEndingWith("JpaRepository")
                    .should().bePackagePrivate()
                    .as("Spring Data repositories must be package-private");
}
