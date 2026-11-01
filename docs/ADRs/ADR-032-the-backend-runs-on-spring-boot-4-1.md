# ADR-032: The backend runs on Spring Boot 4.1, not 3.5

- **Status:** Accepted — its "Jackson 3 with its own defaults" superseded for one feature by
  [ADR-033](ADR-033-refuse-a-fractional-number-for-an-integer-field.md), which turns
  `ACCEPT_FLOAT_AS_INT` off so a fraction sent for an integer field is refused; it also supersedes the
  logger [ADR-027](ADR-027-hibernates-sql-exception-logger-is-turned-off.md) names, `SqlExceptionHelper`,
  with `org.hibernate.orm.jdbc.error`
- **Date:** 2026-10-13
- **Scope:** `backend/pom.xml` and everything the Boot BOM versions: Spring Framework, Spring Security,
  Hibernate, Jackson, Flyway, Micrometer, Testcontainers and JUnit. It retires the version pins that
  [ADR-008](ADR-008-testcontainers-for-the-integration-test-database.md) (Testcontainers) and
  [ADR-010](ADR-010-structured-logging-and-a-level-policy.md) (logstash-logback-encoder) recorded.
- **Issue:** [#73](https://github.com/NaimElijah/UpHealther/issues/73)

## Context

The backend ran Spring Boot 3.2.5. The 3.2 line's open-source support ended on 2024-12-31, and its
commercial support on 2025-12-31. No fix of any kind had been published for it since. The system is
about to run in public (#198, #199), and #59's dependency audit cannot gate a tree that old: every
finding would be "upgrade", which the scanner cannot act on.

#73 proposed 3.5, because the support table gave it the longest window, to 2032. That window is paid
Tanzu support. Its open-source support, the part this project can use, ended on 2026-06-30. Maven
Central agrees: 3.5.16 (2026-06-25) is the last 3.5 release, while 4.0.8 and 4.1.1 shipped on
2026-08-20.

| Line | Open-source support ends | Commercial support ends | Latest release |
|---|---|---|---|
| 3.2 (was) | 2024-12-31 | 2025-12-31 | 3.2.12 |
| 3.5 | **2026-06-30** (passed) | 2032-06-30 | 3.5.16 |
| 4.0 | 2026-12-31 | 2027-12-31 | 4.0.8 |
| **4.1** | **2027-07-31** | 2028-07-31 | 4.1.1 |

Support dates as endoflife.date listed them on 2026-10-13; release dates from Maven Central.

So "3.5, the conservative move" would have landed on a line that was already unpatched. The owner chose
4.1 when this was put to them.

## Decision

**The backend runs Spring Boot 4.1.1.** The move went through 3.5.16 first, as Boot's migration guide
advises, so that everything 4.0 removes appeared as a deprecation while the build still compiled. Each hop
is its own commit, green before the next.

Within the move:

- **Starters are per technology**: `-webmvc`, `-flyway`, and one test starter per slice. That includes
  `-security-test`, without which `@WebMvcTest` orders the security chain after the JWT filter and
  every authenticated slice request answers 401.
- **Tracing comes from `spring-boot-micrometer-tracing-opentelemetry`** beside the existing
  OpenTelemetry bridge, not from the OpenTelemetry starter. The starter would add OTLP exporters, and
  [ADR-007](ADR-007-request-correlation-through-micrometer-tracing.md) keeps tracing for correlation
  only.
- **Jackson 3 with its own defaults.** The application's JSON goes through Boot's Jackson 3 mapper with
  Jackson 3's defaults, not `spring.jackson.use-jackson2-defaults`. This rests on a comparison: every
  endpoint was called against the 3.2.5 build and the 4.1.1 build, and the results compared as parsed
  JSON (#236). That covered every success body, every error status the API answers with, the
  container's error page and the STOMP notification payloads, and they were all identical.
  - The one difference is order. Jackson 3 sorts a class's properties alphabetically, so the API error
    body (a class, not a record) and actuator's health response now list their fields that way.
  - Records keep their declaration order.
  - A JSON object is unordered, and the frontend reads fields by name.
- **jjwt keeps Jackson 2.** jjwt ships no Jackson 3 serializer, up to its latest release (0.13.0), and Boot 4.1 still
  manages Jackson 2 (2.21.5). It parses and writes token claims inside the library and touches no
  application JSON, so two Jackson majors coexist, each in its own package. Moving JWT parsing to
  another serializer is a separate decision.
- **The pins move with the BOM.**
  - ADR-008's Testcontainers 1.21.4 override goes. Boot 4.1 manages Testcontainers 2.0.5, which has
    defaulted to Docker API 1.44 since 2.0.2, so Docker Engine 29 accepts it.
  - ADR-010's logstash-logback-encoder moves from 7.4 to 9.0, its Jackson 3 line.
  - The `flyway-database-postgresql` version pin also goes: it had paired Flyway 10's module with the
    BOM's Flyway 9 core.
- **ADR-027's decision follows Hibernate's logger.** Hibernate 7 writes SQL exceptions, row included,
  under `org.hibernate.orm.jdbc.error` rather than `SqlExceptionHelper`, so that category is now `OFF`.
  `RegistrationRaceIT` caught the move, as ADR-027 said it would. Warnings now have their own category,
  `org.hibernate.orm.jdbc.warn`, and stay on: ADR-027 counted losing them as a cost, not a goal.
- **BCrypt now refuses past 72 bytes.** From Spring Security 6.4.4 and 6.3.8, the fixes for
  CVE-2025-22228, it throws, where it used to truncate silently. The 3.5 hop is the first to bring it in. Registration therefore bounds the password in UTF-8 bytes as well as characters (BR-17),
  before the move reaches the encoder. Sign-in is not bounded, so hashes made before the change still
  verify.

## Consequences

- The backend is on a line that receives security fixes until 2027-07-31, and #59 can gate a
  maintained tree.
- **Two Jackson majors are on the classpath:** 3.1.5 for the application and the log encoder, and
  2.21.5 for jjwt only. The architecture rules forbid both packages in the domain.
- **Two responses changed key order:** the error body and actuator's health response. Nothing reads
  the order, but a snapshot test or a client that compares raw bytes would see a difference.
- **Two JVM metric families were renamed** with Micrometer 1.17:
  - `jvm_gc_overhead_percent` became `jvm_gc_overhead`;
  - `jvm_memory_usage_after_gc_percent` became `jvm_memory_usage_after_gc`.

  A few new families appeared too. Nothing scrapes them yet
  ([#218](https://github.com/NaimElijah/UpHealther/issues/218)), so dashboards and alerts built there
  start from the new names.
- **Boot's test support is modular.** A new slice needs its own test starter, and missing it fails in a
  way that does not name the cause, as the 401s did.
- **4.1's window is shorter than 3.5's commercial one.** Staying supported means moving to Boot's next
  line about once a year. The revisit trigger below sets the date.

## Alternatives considered

- **3.5.16, as #73 proposed.** Rejected. Its open-source support had already ended, so it would have
  meant a second migration before launch, or paying for support.
  - **Revisit:** never. A line past its open-source end only falls further behind.
- **4.0.8.** Rejected. Its open-source support ends on 2026-12-31, eleven weeks from now.
  - **Revisit:** never, for the same reason.
- **Commercial support for 3.5.** Rejected. It is a subscription this project does not have, to stay
  on a major that 4.x has already replaced.
  - **Revisit** if the project takes on a commercial Spring subscription for another reason.
- **Staying on 3.2.** Rejected. It receives no fixes of any kind, and a public deployment is planned.
  - **Revisit:** never.
- **Hibernate 7's `hibernate.jdbc.log.errors=false` in place of turning the logger off.** It is new in
  Hibernate 7 (`JdbcSettings.LOG_JDBC_ERRORS`) and silences the same output without naming a logger
  category, which is the name that just changed. Rejected so that ADR-027's decision stays where it was
  made, in the logging configuration. A property can be renamed as silently as a category, and
  `RegistrationRaceIT` fails on either.
  - **Revisit** if the category is renamed again.
- **`spring.jackson.use-jackson2-defaults=true`.** Rejected. The comparison found no semantic
  difference to preserve. Opting into another major's defaults would be a setting to remove later for
  no gain now.
  - **Revisit** if a client is found that depends on property order.
- **Boot's built-in structured logging (`logging.structured.format.console`) in place of
  logstash-logback-encoder.** ADR-010 deferred this to "when Boot is next upgraded", and this is that
  upgrade. Rejected again, for the reason ADR-010 gave:
  - `StructuredArguments.keyValue` now has 28 call sites in 7 classes, and Boot's built-in format has
    no equivalent.
  - Replacing it means rewriting those lines and re-proving the audit and log-format tests. That is a
    change of its own, not a step in a framework upgrade.

  The encoder moves to 9.0, its Jackson 3 line, and the JSON line it writes keeps the same fields and
  order.
  - **Revisit** when the encoder falls behind Boot's logback or Jackson line, or when a change touches
    those call sites anyway.

## When to revisit

- **2027-01-31**, six months before 4.1's open-source support ends. Move to the line current then.
- **A security fix published only for a later line.**
- **jjwt gains a Jackson 3 serializer**, or Boot stops managing Jackson 2. Then move JWT parsing off
  Jackson 2.
