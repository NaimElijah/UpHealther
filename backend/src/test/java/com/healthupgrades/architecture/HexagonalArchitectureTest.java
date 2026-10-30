package com.healthupgrades.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue; // matches any origin class
import static com.tngtech.archunit.base.DescribedPredicate.not; // inverts an allow-list into a ban
import static com.tngtech.archunit.core.domain.Dependency.Predicates.dependency; // an origin and target pair
import static com.tngtech.archunit.core.domain.Dependency.Predicates.dependencyOrigin; // an origin predicate
import static com.tngtech.archunit.core.domain.Dependency.Predicates.dependencyTarget; // a target predicate
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage; // single-package predicate
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage; // package predicate for ignores
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith; // class-name predicate
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes; // entry point for positive rules
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses; // entry point for forbidding rules

import com.tngtech.archunit.base.DescribedPredicate; // a named predicate, for a rule's exceptions
import com.tngtech.archunit.core.domain.Dependency; // one class's dependency on another
import com.tngtech.archunit.core.domain.JavaClass; // an imported class
import com.tngtech.archunit.core.importer.ImportOption; // controls which classes are analysed
import com.tngtech.archunit.junit.AnalyzeClasses; // declares the packages ArchUnit imports for this test
import com.tngtech.archunit.junit.ArchTest; // marks a field as an executable architecture rule
import com.tngtech.archunit.lang.ArchRule; // the type of a single architecture rule
import com.tngtech.archunit.lang.conditions.ArchConditions; // conditions over a class's dependencies
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition; // builds bounded-context slice rules

/**
 * Enforces the hexagonal (ports and adapters) architecture of the backend.
 *
 * <p>This is the "pragmatic" rule set: the domain is kept strictly framework-free (apart from the JPA
 * mapping annotations the entities carry), Spring Data is confined to the persistence adapters, outbound
 * ports are interfaces, and bounded contexts talk to each other only through shared surfaces (another
 * context's ports, domain model, published domain events, published web DTOs, or the common shared
 * kernel).
 */
@AnalyzeClasses(
        packages = "com.healthupgrades",
        importOptions = ImportOption.DoNotIncludeTests.class) // analyse only production classes
class HexagonalArchitectureTest {

    /**
     * The domain must not depend on frameworks, on adapters, or on the application layer.
     *
     * <p>JPA ({@code jakarta.persistence}) and Lombok are deliberately absent from the forbidden list, so
     * the entities may keep their persistence-mapping annotations — the one compromise ADR-001 accepts.
     * Everything else is listed explicitly rather than relying on the Spring ban alone: without them a
     * domain class could pick up Jackson serialisation, bean-validation constraints or servlet types and
     * nothing would notice.
     *
     * <p>{@code org.slf4j} and {@code io.micrometer} are on the list for the same reason, and neither is
     * caught by any of the others. Logging and measuring are side effects, and side effects belong at
     * the edges: a domain class that writes a log line is one that has quietly acquired an I/O
     * dependency and a reason to be mocked. The audit trail is the shape this is meant to take instead —
     * the domain declares {@code AuditTrail} as a port and an adapter decides that recording means
     * writing a line ({@code ADR-011}).
     */
    @ArchTest
    static final ArchRule domain_is_free_of_framework_and_outer_layers = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.validation..",
                    "jakarta.servlet..",
                    "com.fasterxml.jackson..",
                    "tools.jackson..",
                    "org.hibernate..",
                    "org.slf4j..",
                    "io.micrometer..",
                    "..adapter..",
                    "..application..")
            .as("the domain must not depend on frameworks, adapters, or the application layer");

    /**
     * The domain depends only on what it depends on today, so a library it has never needed cannot arrive
     * unnoticed.
     *
     * <p>The rule above is a deny-list. It names the usual suspects and gives the clearest message when
     * one of them appears, but it says nothing about a library it does not name: an entity that picked up
     * logstash's {@code StructuredArguments}, a JWT type or a JDBC driver class would leave it green. This
     * one is the allow-list behind it. Each entry is here because the domain uses it today:
     *
     * <ul>
     *   <li>{@code java..}: the standard library — identifiers, times, collections, and the secure random
     *       and digest a refresh credential is built from;</li>
     *   <li>{@code jakarta.persistence..}: the mapping annotations the entities carry, the one pragmatic
     *       exception ADR-001 accepts and {@code architecture.md} names, because the entities are the
     *       persistence model;</li>
     *   <li>{@code lombok..}: the builders and accessors Lombok generates. Its annotations are compiled
     *       away, but each generated member keeps {@code @lombok.Generated} in the bytecode;</li>
     *   <li>{@code com.healthupgrades..}: the domain's own types, the kernel's and other contexts' models,
     *       which the layering rules constrain on their own.</li>
     * </ul>
     *
     * <p>Anything else is a decision to record here, with its reason, before a domain class may use it.
     */
    @ArchTest
    static final ArchRule domain_depends_only_on_what_it_needs = classes()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "jakarta.persistence..", "lombok..", "com.healthupgrades..")
            .as("the domain may depend only on the standard library, JPA mapping, Lombok and this codebase");

    /**
     * The application layer must not depend on adapters in either direction. It drives the outbound side
     * through ports (persistence, messaging, events), and states its own input and output shapes —
     * command and result records under {@code application.port.in} — rather than accepting or returning
     * the web adapter's request and response records.
     */
    @ArchTest
    static final ArchRule application_does_not_depend_on_adapters = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .as("the application layer must not depend on adapters");

    /**
     * The core must not reach the kernel's adapters either, whatever their packages are called.
     *
     * <p>ADR-002 promises that no application class imports an adapter, and the rule above keys on
     * {@code ..adapter..}. Most of {@code common} is an adapter by what it holds rather than by its name:
     * {@code common.security} signs and verifies tokens with JJWT and implements Spring Security's types,
     * {@code common.websocket} configures STOMP, {@code common.observability} wires Micrometer, and
     * {@code common.ratelimit} is a handler interceptor. A use case that names one of them is tied to a
     * framework and a key the rule above cannot see. Where the core needs what one of them does, it
     * declares an outbound port and an adapter delegates, as {@code AccessTokenIssuerPort} does for
     * signing. The kernel's own core, {@code common.domain}, stays open to every context.
     */
    @ArchTest
    static final ArchRule core_does_not_depend_on_the_kernels_adapters = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.healthupgrades.common.security..",
                    "com.healthupgrades.common.websocket..",
                    "com.healthupgrades.common.observability..",
                    "com.healthupgrades.common.ratelimit..",
                    "com.healthupgrades.common.adapter..")
            .as("the application and domain layers must not depend on the kernel's adapters (ADR-002)");

    /**
     * Spring Data JPA may only be referenced from the persistence adapters, guaranteeing repositories are
     * hidden behind outbound ports everywhere else.
     */
    @ArchTest
    static final ArchRule spring_data_only_in_persistence_adapters = noClasses()
            .that()
            .resideOutsideOfPackage("..adapter.out.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.data.jpa..", "org.springframework.data.repository..")
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
    static final ArchRule outbound_ports_are_interfaces = classes()
            .that()
            .resideInAnyPackage("..domain.port.out..", "..application.port.out..")
            .and()
            .haveSimpleNameEndingWith("Port")
            .should()
            .beInterfaces()
            .as("outbound ports must be interfaces");

    /** Ports belong in a port package, so the rules that constrain them cannot be sidestepped. */
    @ArchTest
    static final ArchRule ports_live_in_a_port_package = classes()
            .that()
            .haveSimpleNameEndingWith("Port")
            .should()
            .resideInAnyPackage("..domain.port.out..", "..application.port.out..")
            .as("classes named *Port must live in a port package");

    /**
     * Bounded contexts must not depend on each other's internals. Cross-context dependencies are allowed
     * only to shared surfaces: the common shared kernel, another context's domain model, its inbound
     * ports, or its published web DTOs.
     */
    @ArchTest
    static final ArchRule bounded_contexts_only_share_through_ports_and_published_models = SlicesRuleDefinition.slices()
            .matching("com.healthupgrades.(*)..") // one slice per top-level package (context)
            .namingSlices("$1")
            .as("bounded contexts")
            .should()
            .notDependOnEachOther()
            .ignoreDependency(
                    alwaysTrue(),
                    resideInAnyPackage(
                                    "com.healthupgrades.common..", // shared kernel + cross-cutting adapters
                                    "..domain.model..", // another context's domain model is shareable
                                    "..domain.event..", // published domain events are published language
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
    static final ArchRule bounded_contexts_are_free_of_cycles = SlicesRuleDefinition.slices()
            .matching("com.healthupgrades.(*)..")
            .namingSlices("$1")
            .as("bounded contexts")
            .should()
            .beFreeOfCycles();

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
    static final ArchRule inbound_adapters_do_not_depend_on_outbound_adapters = noClasses()
            .that()
            .resideInAPackage("..adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter.out..")
            .as("inbound adapters must not depend on outbound adapters");

    /**
     * A driving adapter triggers a use case; it does not perform one.
     *
     * <p>An adapter that reads a repository, publishes an event or writes an audit entry itself is
     * holding a use case, and a second trigger for the same work — an endpoint beside a cron, say — has
     * nothing to call and copies the rules instead. Services orchestrate: load, call the domain method,
     * save, publish (ADR-002). So a class in {@code ..adapter.in..} reaches no outbound port, and the
     * scheduled sweeps and the administrator bootstrap call application services that do.
     *
     * <p>Two exceptions, each a pair of an origin and a target rather than a package let off:
     * <ul>
     *   <li><strong>A composition adapter supplying a port the kernel declares.</strong>
     *       {@code SessionStatusAdapter} and {@code AccountCredentialsAdapter} implement
     *       {@code SessionStatusPort} and {@code AccountCredentialsPort}, the supplying half of an
     *       inverted dependency (ADR-015, ADR-062). Implementing a port is not driving one, and the
     *       kernel's records they return are part of the contract. The exception covers only targets in
     *       {@code common.domain.port.out}, so a composition adapter still may not read a context's
     *       repository.</li>
     *   <li><strong>{@code SessionEndSocketListener} closing sockets through {@code LiveSocketPort}.</strong>
     *       It translates an event whose decision is already made — {@code AuthSessionService} ended the
     *       session and published it — into the one call that carries it out, with no rule of its own to
     *       duplicate (ADR-054). A use case in front of it would be a pass-through. Any other
     *       {@code ..adapter.in.event..} class gets no such leave.</li>
     * </ul>
     */
    @ArchTest
    static final ArchRule inbound_adapters_do_not_drive_outbound_ports = classes()
            .that()
            .resideInAPackage("..adapter.in..")
            .should(ArchConditions.onlyHaveDependenciesWhere(
                    not(dependencyTarget(resideInAPackage("..domain.port.out..")))
                            .or(dependency(
                                    resideInAPackage("..adapter.in.composition.."),
                                    resideInAPackage("com.healthupgrades.common.domain.port.out..")))
                            .or(dependency(
                                    "com.healthupgrades.auth.adapter.in.event.SessionEndSocketListener",
                                    "com.healthupgrades.common.domain.port.out.LiveSocketPort"))))
            .as("inbound adapters must reach the core through use cases, not through outbound ports (ADR-002)");

    /**
     * Use-case and query ports are contracts, so they must be interfaces. Command and result records
     * live in the same package by design and are matched by name rather than by package.
     */
    @ArchTest
    static final ArchRule inbound_ports_are_interfaces = classes()
            .that()
            .resideInAPackage("..application.port.in..")
            .and()
            .haveSimpleNameEndingWith("Query")
            .or(resideInAPackage("..application.port.in..").and(simpleNameEndingWith("Command")))
            .should()
            .beInterfaces()
            .as("inbound query and command ports must be interfaces");

    /**
     * A context's ports speak only in published types.
     *
     * <p>ADR-002 makes a context's ports its published surface, and the slice rule above lets another
     * context reach only {@code ..application.port..}, {@code ..domain.model..} and a few other published
     * packages. A port that takes or returns a type from anywhere else hands its first cross-context
     * caller an import that the slice rule refuses, and the easy fix is then to widen the slice rule. So
     * whatever a port names lives where its callers may import it. Each allowed package is here for a
     * reason:
     *
     * <ul>
     *   <li>{@code java..}: identifiers, times and collections, and what every record compiles against;
     *   <li>{@code ..application.port..}: the command and result records that sit beside the ports, and
     *       another context's ports, as {@code UpgradeTrackingSummaryPort} names its own record;
     *   <li>{@code ..domain.model..}: the aggregates and enums a query returns, which the slice rule
     *       already publishes;
     *   <li>{@code com.healthupgrades.common.domain..}: the kernel's own model, which every context
     *       already shares. No port names it today.
     * </ul>
     *
     * <p>No framework is listed, because no port imports one: a port record that wants a JSpecify,
     * Lombok or bean-validation annotation is a decision to add here, not something to slip in.
     */
    @ArchTest
    static final ArchRule ports_name_only_published_types = classes()
            .that()
            .resideInAPackage("..application.port..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..", "..application.port..", "..domain.model..", "com.healthupgrades.common.domain..")
            .as("ports must take and return only published types (ADR-002)");

    /**
     * The application services that run scheduled work, and the only classes allowed a sweep port. Named
     * by class, so a new one is a deliberate entry here rather than something a new caller slips into.
     */
    private static final DescribedPredicate<JavaClass> SCHEDULED_WORK = JavaClass.Predicates.belongToAnyOf(
                    com.healthupgrades.notification.application.NotificationSweepService.class,
                    com.healthupgrades.upgrade.application.UpgradeOverdueService.class)
            .as("the jobs' application services");

    /**
     * A sweep port reads every user's rows, so only the scheduled jobs may hold one.
     *
     * <p>User scoping is enforced at the query layer (BR-15): every read a request can reach takes the
     * owner's id, so another user's record comes back as absent. The sweeps need the opposite — every
     * active upgrade, every enabled reminder, everybody who logged today — and those reads live on a
     * {@code *SweepQuery} port of their own rather than beside the user-scoped ones, where a feature taking
     * an id from a request could pick one up and return somebody else's row with nothing failing.
     *
     * <p>A dependent is allowed only if it is one of {@link #SCHEDULED_WORK}, or if it implements the port.
     * Anything else that wants every user's rows is a decision to add here, not an import.
     *
     * <p>The rule sees only dependencies on the port itself, so each port is implemented by a class of its
     * own — {@code UpgradeSweeps}, {@code ProgressSweeps}, {@code ReminderSweeps} — and never by the
     * service a controller holds. Implemented there, the methods would be one call away from a request
     * handler through the concrete service, past this rule.
     */
    @ArchTest
    static final ArchRule sweep_ports_serve_only_scheduled_work = classes()
            .that()
            .resideInAPackage("..application.port.in..")
            .and()
            .haveSimpleNameEndingWith("SweepQuery")
            .should(ArchConditions.onlyHaveDependentsWhere(dependencyOrigin(SCHEDULED_WORK)
                    .or(new DescribedPredicate<Dependency>("its origin implements the port") {
                        @Override
                        public boolean test(Dependency dependency) {
                            return dependency
                                    .getOriginClass()
                                    .isAssignableTo(dependency.getTargetClass().getName());
                        }
                    })))
            .as("sweep ports must be used only by the scheduled jobs' application services (BR-15)");

    /**
     * The one unscoped by-ids read stays inside the context that owns it.
     *
     * <p>{@code TrackingConfigQuery.findByUpgradeIds} returns configurations whoever owns the upgrade,
     * because {@code tracking_configs} has no owner column to filter on. Today that exposes nothing: its
     * dependents are {@code TrackingService}, which implements it, and {@code UpgradeTrackingSummaryAdapter},
     * which answers {@code UpgradeTrackingSummaryPort} for {@code UpgradeWebMapper}. The mapper passes only
     * the ids of upgrades it was handed, and those were loaded through user-scoped reads, so no id a request
     * supplies reaches the query. Scoping it would cost a migration, a join onto another context's table,
     * or a query per response, to close a path that does not exist.
     *
     * <p>This rule is what keeps that true. It is wrong, and the port must be scoped first, the day a class
     * outside {@code tracking} needs it, or the day an id from a request reaches it (BR-15).
     */
    @ArchTest
    static final ArchRule unscoped_tracking_config_read_stays_inside_tracking = classes()
            .that()
            .haveFullyQualifiedName("com.healthupgrades.tracking.application.port.in.TrackingConfigQuery")
            .should()
            .onlyHaveDependentClassesThat()
            .resideInAPackage("com.healthupgrades.tracking..")
            .as("TrackingConfigQuery, which is not scoped by owner, must be used only inside tracking (BR-15)");

    /** Controllers are a web concern and belong only in the web adapter. */
    @ArchTest
    static final ArchRule controllers_live_only_in_the_web_adapter = classes()
            .that()
            .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .or()
            .areAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
            .or()
            .areAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
            .should()
            .resideInAPackage("..adapter.in.web..")
            .as("controllers must live in the web adapter");

    /**
     * The administration context may not reach a user's own records.
     *
     * <p>Everything else in this class is about layering. This one is about a promise to users, and
     * it is enforced here because there is nowhere else it could be: the guarantee is that an
     * administrator gains <em>paths</em> and not <em>rows</em>, and what makes that true is that
     * {@code admin} has no way to reach a health context at all. A reviewer cannot see the absence of
     * an import; this rule can (ADR-016).
     *
     * <p>An allow-list rather than a list of the forbidden contexts, so a context added later is out of
     * reach until somebody decides otherwise: {@code admin} manages accounts through {@code user} and
     * ends sessions through {@code auth}, and reaches nothing else of this codebase but {@code common}.
     */
    @ArchTest
    static final ArchRule admin_cannot_reach_a_users_own_records = noClasses()
            .that()
            .resideInAPackage("com.healthupgrades.admin..")
            .should()
            .dependOnClassesThat(resideInAPackage("com.healthupgrades..")
                    .and(not(resideInAnyPackage(
                            "com.healthupgrades.admin..",
                            "com.healthupgrades.user..",
                            "com.healthupgrades.auth..",
                            "com.healthupgrades.common.."))))
            .as("the admin context must not depend on any context holding a user's own records: it may reach "
                    + "only user, auth and common");

    /**
     * The shared kernel depends on no bounded context.
     *
     * <p>Every context depends on {@code common}, so an arrow from {@code common} into a context makes that
     * context a dependency of every other one, and leaves it unable to use the kernel without closing a
     * cycle. Where the kernel needs something a context knows, it declares an outbound port in
     * {@code common.domain.port.out} and the context supplies it, as {@code SessionStatusPort} and
     * {@code AccountCredentialsPort} are supplied (ADR-062).
     *
     * <p>The cycle rule above does not cover this. It permits an edge from the kernel into a context for as
     * long as that context imports nothing from the kernel, which is where {@code user} was held while the
     * security adapter called it. An allow-list rather than a list of the ten contexts, so a context added
     * later is out of the kernel's reach from its first commit.
     */
    @ArchTest
    static final ArchRule shared_kernel_depends_on_no_bounded_context = noClasses()
            .that()
            .resideInAPackage("com.healthupgrades.common..")
            .should()
            .dependOnClassesThat(
                    resideInAPackage("com.healthupgrades..").and(not(resideInAPackage("com.healthupgrades.common.."))))
            .as("the shared kernel must not depend on any bounded context: common may reach only common");

    /**
     * Spring Data repositories stay package-private, so reaching past a port into another context's
     * persistence is impossible by compilation rather than by discipline.
     */
    @ArchTest
    static final ArchRule spring_data_repositories_are_package_private = classes()
            .that()
            .haveSimpleNameEndingWith("JpaRepository")
            .should()
            .bePackagePrivate()
            .as("Spring Data repositories must be package-private");
}
