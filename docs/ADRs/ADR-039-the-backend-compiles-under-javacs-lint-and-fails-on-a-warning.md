# ADR-039: The backend compiles under javac's lint, and fails on a warning

- **Status:** Accepted
- **Date:** 2026-10-15
- **Scope:** the `maven-compiler-plugin` configuration in `backend/pom.xml`. No new dependency.
- **Issue:** [#162](https://github.com/NaimElijah/UpHealther/issues/162), step 1. Step 2, the
  bug-pattern checker, is [ADR-040](ADR-040-no-bug-pattern-checker-yet.md).

## Context

NFR-12 says every push is "built, tested, linted". Only the frontend was linted.

The backend has ArchUnit (`HexagonalArchitectureTest`, `ServerTimeArchitectureTest`). It checks the
dependencies between classes, and nothing checks the code inside them. The compiler ran with the Boot
parent's configuration: `parameters` and `release`, its default warnings, and no `-Werror`.

`javac --release 21 -parameters -Xlint:all` over `src/main` and `src/test` on Boot 4.1.1 found 17
warnings, plus one `[processing]` warning per compilation, which the exclusions below turn off. The 17
are the same that #162 counted on Boot 3.2:

| Category | Count | Where | What it is |
|---|---|---|---|
| `[serial]` | 9 | The seven exceptions in `common/domain/exception/`, `EmailAlreadyRegisteredException`, `SecurityUser` | A `Serializable` type, by inheritance, with no `serialVersionUID` |
| `[try]` | 6 | `CorrelationIdTest`, `TraceIdResponseHeaderFilterTest` (2), `TraceIdErrorAttributesTest`, `LogOutputFormatTest`, `GlobalExceptionHandlerTest` | `try (Tracer.SpanInScope ignored = ...)`, a resource opened for its side effect and never named |
| `[unchecked]` | 2 | One site: `ArgumentCaptor.forClass(Supplier.class)` in `NotificationEventListenerTest` | A raw `Class` standing in for `Supplier<String>` |
| `[processing]` | 1 per compilation | — | Lombok is found on the classpath, and javac lists every annotation no processor claims |

None of them is a defect. That is the reason to set the floor now, while the list is this short.

## Decision

**Compile with `-Xlint:all,-processing,-serial`, and fail on any warning (`failOnWarning`, which passes
`-Werror`). Test sources also turn off `try`.**

- **The lint runs in `compile` and `test-compile`.** `./mvnw test` and CI's `verify` therefore fail
  identically. CI needs no step of its own.
- **`[unchecked]` was fixed.** The captor became a `@Captor` field, which `MockitoExtension` builds from
  the field's generic type.
- **`serial` is off, and not answered with nine `serialVersionUID`s.** Nothing in this application uses
  Java serialization:
  - the security chain is `STATELESS`;
  - a session is a database row (ADR-015), not an `HttpSession`;
  - `src/main` has no object stream and no `Serializable` of its own.

  A `serialVersionUID` would promise a compatibility that nobody relies on.
- **`try` is off in test sources only.** An unnamed scope is how a span is put in scope; the test
  closes it on purpose and never refers to it. Main code keeps the check.
- **`processing` is off.** The warning is about how Lombok is discovered, not about this code.
- **Each exclusion's reason is written beside it** in `pom.xml`.

## Consequences

- **A new warning in any enabled category fails the build:** a raw type, an unchecked cast, a
  deprecation, a fall-through, a `this` escaping a constructor, and so on. A raw `java.util.List` added
  to `ServerZone` fails `./mvnw compile` with "warnings found and -Werror specified".
- **`@SuppressWarnings` becomes the way to accept a warning.** Like an ESLint disable, it needs a
  comment saying why.
- **A deprecation in a dependency upgrade fails the build** until the call is moved off the deprecated
  API. That is the intended effect, and it can make a framework upgrade one commit larger.
- **javac prints a note about implicit annotation processing.** JDK 21 notes that a future release may
  stop discovering processors on the classpath, and JDK 23 did. Moving the JDK past 21 therefore means
  naming Lombok explicitly: `annotationProcessorPaths`, or `-proc:full`. The note is not a warning, so
  it does not fail the build.

## Alternatives considered

- **Answer `serial` with `serialVersionUID`s.** Rejected. Nine declarations would promise a compatibility
  nobody relies on, and every new exception would need one more.
  - **Revisit:** if anything starts serializing these types, such as a distributed session store or a
    cache holding exceptions.
- **`@SuppressWarnings("try")` on each of the six test scopes.** Rejected. Six annotations with the same
  reason say less than one exclusion with the reason beside it.
  - **Revisit:** if a main-code site needs the idiom. There it gets its own `@SuppressWarnings` with its
    reason.
- **Lint without `-Werror`.** Rejected. Warnings that nobody is made to read accumulate, which is how a
  floor of 17 becomes 170.
  - **Revisit:** never on its own.
- **A style linter (Checkstyle or PMD).** Rejected. Layout belongs to the formatter (ADR-037), and a
  style linter would duplicate it.
  - **Revisit:** see ADR-037.

## When to revisit

- **The JDK moves past 21.** New lint categories arrive with it, and Lombok must be named as a
  processor. See Consequences.
- **Java serialization enters the application** in any form. Turn `serial` back on.
