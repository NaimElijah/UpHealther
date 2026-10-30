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

import com.healthupgrades.admin.application.AdminUserService; // the one audited reader of every account
import com.healthupgrades.dashboard.adapter.in.web.DashboardController; // the dashboard mapper's one caller
import com.healthupgrades.dashboard.adapter.in.web.DashboardWebMapper; // the last hop to the tracking read
import com.healthupgrades.tracking.adapter.in.composition.UpgradeTrackingSummaryAdapter; // the read's one user
import com.healthupgrades.tracking.application.port.in.TrackingConfigQuery; // an unscoped read port
import com.healthupgrades.user.application.port.in.AccountListQuery; // an unscoped read port
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
     * A dependency whose origin implements its target: the supplying side of a port, which is not a use of
     * it. Declared first, because the rules below are initialised in textual order and read it.
     */
    private static final DescribedPredicate<Dependency> ORIGIN_IMPLEMENTS_TARGET =
            new DescribedPredicate<>("its origin implements its target") {
                @Override
                public boolean test(Dependency dependency) {
                    return dependency
                            .getOriginClass()
                            .isAssignableTo(dependency.getTargetClass().getName());
                }
            };

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
     *   <li>{@code java.lang..}: what every class compiles against, records, enums and lambdas included;</li>
     *   <li>{@code java.util..}: identifiers, collections, {@code Optional}, and the {@code function} and
     *       {@code stream} types the aggregates use;</li>
     *   <li>{@code java.time..}: dates, times and durations;</li>
     *   <li>{@code java.security..} and {@code java.nio.charset..}: the SHA-256 digest a refresh credential is
     *       stored as, and the charset its bytes are read in;</li>
     *   <li>{@code jakarta.persistence..}: the mapping annotations the entities carry, the one pragmatic
     *       exception ADR-001 accepts and {@code architecture.md} names, because the entities are the
     *       persistence model. {@link #domain_uses_jpa_only_to_map} keeps the persistence runtime out;</li>
     *   <li>{@code lombok..}: the builders and accessors Lombok generates. Its annotations are compiled
     *       away, but each generated member keeps {@code @lombok.Generated} in the bytecode;</li>
     *   <li>{@code com.healthupgrades..}: the domain's own types, the kernel's and other contexts' models,
     *       which the layering rules constrain on their own.</li>
     * </ul>
     *
     * <p>The JDK is listed by package, not as {@code java..}, because {@code java..} also admits
     * {@code java.sql}, {@code java.net}, file I/O in {@code java.io} and {@code java.util.logging}. None of
     * those is used, and each is the I/O this rule exists to keep out. Anything else is a decision to
     * record here, with its reason, before a domain class may use it (ADR-064).
     */
    @ArchTest
    static final ArchRule domain_depends_only_on_what_it_needs = classes()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java.lang..",
                    "java.util..",
                    "java.time..",
                    "java.security..",
                    "java.nio.charset..",
                    "jakarta.persistence..",
                    "lombok..",
                    "com.healthupgrades..")
            .as("the domain may depend only on the JDK packages it uses, JPA mapping, Lombok and this codebase");

    /**
     * The domain uses JPA to describe its mapping, never to run a query.
     *
     * <p>The allow-list above has to admit {@code jakarta.persistence}, because the entities carry its
     * annotations, and so it would also admit the persistence runtime: an entity holding an
     * {@code EntityManager}, or a domain service building a {@code TypedQuery}, would be the domain doing its
     * own I/O, which is what the repository ports and their adapters are for. The runtime types and the
     * criteria, metamodel and provider packages are therefore banned here by name (ADR-064).
     */
    @ArchTest
    static final ArchRule domain_uses_jpa_only_to_map = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                            jakarta.persistence.EntityManager.class,
                            jakarta.persistence.EntityManagerFactory.class,
                            jakarta.persistence.EntityTransaction.class,
                            jakarta.persistence.Query.class,
                            jakarta.persistence.TypedQuery.class,
                            jakarta.persistence.StoredProcedureQuery.class,
                            jakarta.persistence.Persistence.class,
                            jakarta.persistence.PersistenceUnitUtil.class,
                            jakarta.persistence.PersistenceUtil.class)
                    .or(resideInAnyPackage(
                            "jakarta.persistence.criteria..",
                            "jakarta.persistence.metamodel..",
                            "jakarta.persistence.spi..")))
            .as("the domain may use JPA's mapping annotations only, never its runtime");

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
     * The core reaches the kernel only through the kernel's own core, whatever the kernel's other packages
     * are called.
     *
     * <p>ADR-002 promises that no application class imports an adapter, and the rule above keys on
     * {@code ..adapter..}. Most of {@code common} is an adapter by what it holds rather than by its name:
     * {@code common.security} signs and verifies tokens with JJWT and implements Spring Security's types,
     * {@code common.websocket} configures STOMP, {@code common.observability} wires Micrometer, and
     * {@code common.ratelimit} is a handler interceptor. A use case that names one of them is tied to a
     * framework and a key the rule above cannot see.
     *
     * <p>So this is an allow-list rather than a list of those packages: {@code ..application..} and
     * {@code ..domain..} may reach {@code com.healthupgrades.common..} only in {@code common.domain}, its
     * events, exceptions, audit vocabulary and ports. A kernel package added later, {@code common.mail} say,
     * is out of the core's reach from its first commit. {@code common.time} is outside too, and unused by the
     * core: the core reads the injected {@code Clock}, and only the schedulers name {@code ServerZone}. Where
     * the core needs what a kernel adapter does, it declares an outbound port and an adapter delegates, as
     * {@code AccessTokenIssuerPort} does for signing (ADR-064).
     */
    @ArchTest
    static final ArchRule core_does_not_depend_on_the_kernels_adapters = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..domain..")
            .should()
            .dependOnClassesThat(resideInAPackage("com.healthupgrades.common..")
                    .and(not(resideInAPackage("com.healthupgrades.common.domain.."))))
            .as("the application and domain layers may reach the kernel only through common.domain (ADR-064)");

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
     * save, publish (ADR-064). So a class in {@code ..adapter.in..} reaches no outbound port, under
     * {@code domain.port.out} or {@code application.port.out} alike, and the scheduled sweeps and the
     * administrator bootstrap call application services that do.
     *
     * <p>Three exceptions, each an origin and a target rather than a package let off:
     * <ul>
     *   <li><strong>A composition adapter implementing a port.</strong> {@code SessionStatusAdapter},
     *       {@code AccountCredentialsAdapter} and {@code UpgradeTrackingSummaryAdapter} supply the half of
     *       an inverted dependency that the declaring side cannot (ADR-015, ADR-062, ADR-002).
     *       Implementing a port is not driving one, so the exception covers only a port the origin itself
     *       implements, and the two records those ports return, {@code AccountCredentials} and
     *       {@code UpgradeTrackingSummary}. A composition adapter still may not publish an event, write an
     *       audit entry or read a repository.</li>
     *   <li><strong>{@code SessionEndSocketListener} closing sockets through {@code LiveSocketPort}.</strong>
     *       It translates an event whose decision is already made — {@code AuthSessionService} ended the
     *       session and published it — into the one call that carries it out, with no rule of its own to
     *       duplicate (ADR-054). A use case in front of it would be a pass-through. Any other
     *       {@code ..adapter.in.event..} class gets no such leave.</li>
     *   <li><strong>{@code UpgradeWebMapper} reading {@code UpgradeTrackingSummaryPort}.</strong> The
     *       upgrade response shows each upgrade's tracking setup, and the mapper resolves it for the
     *       aggregates it was handed, in one batch, so the use cases need not return a web shape. The
     *       mapper reads configurations whoever owns them, and
     *       {@link #tracking_summary_port_serves_only_the_upgrade_mapper} is what keeps any other class off
     *       the port.</li>
     * </ul>
     *
     * <p>The kernel's own security adapters, {@code BearerTokenAuthenticator} and
     * {@code UserDetailsServiceImpl}, call {@code SessionStatusPort} and {@code AccountCredentialsPort}
     * by design: those ports are the kernel's, inverted so that a context can supply them. They live in
     * {@code common.security}, outside {@code ..adapter.in..}, so this rule does not see them.
     */
    @ArchTest
    static final ArchRule inbound_adapters_do_not_drive_outbound_ports = classes()
            .that()
            .resideInAPackage("..adapter.in..")
            .should(ArchConditions.onlyHaveDependenciesWhere(not(dependencyTarget(resideInAPackage("..port.out..")))
                    .or(dependencyOrigin(resideInAPackage("..adapter.in.composition.."))
                            .and(ORIGIN_IMPLEMENTS_TARGET))
                    .or(dependency(
                            resideInAPackage("..adapter.in.composition.."),
                            JavaClass.Predicates.belongToAnyOf(
                                    com.healthupgrades.common.domain.port.out.AccountCredentials.class,
                                    com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummary.class)))
                    .or(dependency(
                            "com.healthupgrades.auth.adapter.in.event.SessionEndSocketListener",
                            "com.healthupgrades.common.domain.port.out.LiveSocketPort"))
                    .or(dependency(
                            "com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper",
                            "com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort"))
                    .or(dependency(
                            "com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper",
                            "com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummary"))))
            .as("inbound adapters must reach the core through use cases, not through outbound ports (ADR-064)");

    /**
     * The tracking summary port, which reads configurations whoever owns them, serves the upgrade mapper
     * and nothing else.
     *
     * <p>{@code UpgradeTrackingSummaryPort.findByUpgradeIds} passes straight through to
     * {@code TrackingConfigQuery.findByUpgradeIds}, which is not scoped by owner. Pinning the query inside
     * {@code tracking} would protect nothing if this port, which the slice rule publishes to every context,
     * were open: an endpoint taking upgrade ids from a request could read another user's tracking setup
     * through it (BR-15). So it has two dependents: {@code UpgradeWebMapper}, which passes only the ids of
     * aggregates already loaded through user-scoped reads, and {@code UpgradeTrackingSummaryAdapter}, which
     * implements it. With {@link #unscoped_tracking_config_read_serves_only_the_summary_adapter} it is the
     * second of the hops that pin one read, the trade-off ADR-064 accepts and says when to revisit.
     */
    @ArchTest
    static final ArchRule tracking_summary_port_serves_only_the_upgrade_mapper = classes()
            .that()
            .haveFullyQualifiedName("com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort")
            .should()
            .onlyHaveDependentClassesThat(JavaClass.Predicates.belongToAnyOf(
                    com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper.class,
                    com.healthupgrades.tracking.adapter.in.composition.UpgradeTrackingSummaryAdapter.class))
            .as("UpgradeTrackingSummaryPort, which is not scoped by owner, must be used only by UpgradeWebMapper "
                    + "and its implementer (BR-15)");

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
            .as("ports must take and return only published types (ADR-064)");

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
     * Anything else that wants every user's rows is a decision to add here, not an import (ADR-064).
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
            .should(ArchConditions.onlyHaveDependentsWhere(
                    dependencyOrigin(SCHEDULED_WORK).or(ORIGIN_IMPLEMENTS_TARGET)))
            .as("sweep ports must be used only by the scheduled jobs' application services (BR-15)");

    /**
     * The one unscoped by-ids read serves the one adapter that passes it on, and nothing else.
     *
     * <p>{@code TrackingConfigQuery.findByUpgradeIds} returns configurations whoever owns the upgrade,
     * because {@code tracking_configs} has no owner column to filter on. Today that exposes nothing: its
     * dependents are {@code TrackingService}, which implements it, and {@code UpgradeTrackingSummaryAdapter},
     * which answers {@code UpgradeTrackingSummaryPort} for {@code UpgradeWebMapper}. The mapper passes only
     * the ids of upgrades it was handed, and those were loaded through user-scoped reads, so no id a request
     * supplies reaches the query. Scoping it would cost a migration, a join onto another context's table,
     * or a query per response, to close a path that does not exist.
     *
     * <p>This rule keeps that true at the port: its one user is {@code UpgradeTrackingSummaryAdapter}, and
     * any other class, in {@code tracking} or not, would be a new way to the read.
     * {@link #tracking_summary_port_serves_only_the_upgrade_mapper} keeps it true one hop out, where the
     * adapter passes the same read through {@code UpgradeTrackingSummaryPort}, which the slice rule publishes
     * to every context, and {@link #upgrade_web_mapper_serves_only_its_two_callers} and
     * {@link #dashboard_web_mapper_serves_only_its_controller} the hops after that. Any pin alone would leave
     * the read open. The port must be scoped first, the day another class needs it, a class other than
     * {@code UpgradeWebMapper} needs the summary port, or an id from a request reaches either (BR-15).
     */
    @ArchTest
    static final ArchRule unscoped_tracking_config_read_serves_only_the_summary_adapter = classes()
            .that()
            .haveFullyQualifiedName(TrackingConfigQuery.class.getName())
            .should(ArchConditions.onlyHaveDependentsWhere(
                    dependencyOrigin(JavaClass.Predicates.belongToAnyOf(UpgradeTrackingSummaryAdapter.class))
                            .or(ORIGIN_IMPLEMENTS_TARGET)))
            .as("TrackingConfigQuery, which is not scoped by owner, must be used only by "
                    + "UpgradeTrackingSummaryAdapter and its implementer (BR-15)");

    /**
     * The ports whose reads are not scoped by owner, named once for the rules below: every sweep port, by
     * the predicate {@link #sweep_ports_serve_only_scheduled_work} selects them with, and the two others by
     * class literal, so a rename is a compile error rather than a rule that quietly stops matching.
     */
    private static final DescribedPredicate<JavaClass> UNSCOPED_READ_PORT = resideInAPackage("..application.port.in..")
            .and(simpleNameEndingWith("SweepQuery"))
            .or(JavaClass.Predicates.belongToAnyOf(TrackingConfigQuery.class, AccountListQuery.class))
            .as("a port whose reads are not scoped by owner");

    /**
     * An unscoped read is implemented by a class nothing outside its package can name.
     *
     * <p>The rules on each port see only dependencies on the port. A public class implementing it is a
     * second way in: a controller could inject the class and call the read with an id from a request, and
     * no port rule would notice (S5.B6). So each one is implemented by a package-private class of its own,
     * never by a public service, and Spring wires the port to it. This extends ADR-064's sweep rule to every
     * unscoped read (ADR-065).
     */
    @ArchTest
    static final ArchRule unscoped_reads_are_implemented_by_package_private_classes = classes()
            .that(JavaClass.Predicates.implement(UNSCOPED_READ_PORT))
            .should()
            .notBePublic()
            .as("a class implementing an unscoped read must be package-private (BR-15)");

    /**
     * The upgrade mapper, which reads tracking configurations for whatever upgrades it is handed, serves
     * the two web classes that hand it upgrades loaded through user-scoped reads, and nothing else.
     *
     * <p>{@code UpgradeWebMapper.toDtos} calls {@code UpgradeTrackingSummaryPort}, so the pin on that port
     * holds only while every caller of the mapper is one that cannot pass it somebody else's upgrade. The
     * slice rule publishes the upgrade context's web DTOs, and the mapper beside them, to every context;
     * this closes that hop (S5.B6). A new caller is a decision to add here, after checking where its
     * upgrades come from.
     */
    @ArchTest
    static final ArchRule upgrade_web_mapper_serves_only_its_two_callers = classes()
            .that()
            .haveFullyQualifiedName("com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper")
            .should()
            .onlyHaveDependentClassesThat(JavaClass.Predicates.belongToAnyOf(
                    com.healthupgrades.upgrade.adapter.in.web.UpgradeController.class,
                    com.healthupgrades.dashboard.adapter.in.web.DashboardWebMapper.class))
            .as("UpgradeWebMapper must be used only by UpgradeController and DashboardWebMapper (BR-15)");

    /**
     * The dashboard mapper, which hands {@code UpgradeWebMapper} whatever upgrades a {@code DashboardView}
     * carries, serves the controller that builds that view from user-scoped reads, and nothing else.
     *
     * <p>It is a public {@code @Component}, the slice rule publishes every {@code *WebMapper} to every
     * context, and {@code DashboardView} is a public record anybody can build. Without this a controller
     * anywhere could build a view of somebody else's upgrades and have their tracking configurations read
     * for it, with every other pin green (S5.B6).
     */
    @ArchTest
    static final ArchRule dashboard_web_mapper_serves_only_its_controller = classes()
            .that()
            .haveFullyQualifiedName(DashboardWebMapper.class.getName())
            .should()
            .onlyHaveDependentClassesThat(JavaClass.Predicates.belongToAnyOf(DashboardController.class))
            .as("DashboardWebMapper must be used only by DashboardController (BR-15)");

    /**
     * The read of every account serves the administration context alone.
     *
     * <p>{@code AccountListQuery} pages through every account on the installation, names and addresses
     * included. On {@code UserQuery}, which every context may reach, a feature anywhere could have listed
     * them (S5.B6). It is a port of its own, and only {@code AdminUserService}, which writes the
     * {@code admin.list} audit entry around the read, and its implementer may depend on it: an admin
     * controller holding the port would list every account and audit nothing (S5.A12).
     */
    @ArchTest
    static final ArchRule account_listing_serves_only_the_audited_admin_service = classes()
            .that()
            .haveFullyQualifiedName(AccountListQuery.class.getName())
            .should(ArchConditions.onlyHaveDependentsWhere(
                    dependencyOrigin(JavaClass.Predicates.belongToAnyOf(AdminUserService.class))
                            .or(ORIGIN_IMPLEMENTS_TARGET)))
            .as("AccountListQuery, which reads every account, must be used only by AdminUserService and its "
                    + "implementer (BR-15)");

    /**
     * Only the startup runner may promote the first administrator.
     *
     * <p>{@code AdminBootstrapService.bootstrap} grants ADMIN with no administrator asking, and its one guard
     * is that no enabled administrator exists yet. Behind an endpoint, on a fresh installation, that guard
     * would let any signed-in user make themselves the installation's administrator. So the use case has a
     * service of its own, and nothing but {@code AdminBootstrapRunner}, which reads the deployment's
     * setting at startup, may hold it (ADR-016).
     */
    @ArchTest
    static final ArchRule only_the_runner_bootstraps_an_administrator = classes()
            .that()
            .haveFullyQualifiedName("com.healthupgrades.admin.application.AdminBootstrapService")
            .should()
            .onlyHaveDependentClassesThat()
            .haveFullyQualifiedName("com.healthupgrades.admin.adapter.in.bootstrap.AdminBootstrapRunner")
            .as("only AdminBootstrapRunner may depend on AdminBootstrapService");

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
