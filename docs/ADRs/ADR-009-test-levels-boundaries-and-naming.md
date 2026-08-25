# ADR-009: Four test levels, each with a boundary, and one naming convention

- **Status:** Accepted
- **Date:** 2026-08-25
- **Scope:** the whole repository — `backend/`, `frontend/`, and what CI gates on.

## Context

The suite grew a level at a time, each addition sound on its own and none of them written down together.
[ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md) added ArchUnit and the
first integration test, [ADR-004](ADR-004-frontend-test-harness.md) added the frontend harness,
[ADR-007](ADR-007-request-correlation-through-micrometer-tracing.md) added tests that assert on spans, and
[ADR-008](ADR-008-testcontainers-for-the-integration-test-database.md) settled where the integration
database comes from. What none of them says is which level a *new* test belongs at, or how far down it is
allowed to reach.

The cost of leaving that unstated showed up as a coverage audit for issue #46:

- `docs/requirements/requirements.md` promises that each requirement names "the enforcing class or test".
  Most rows named a class, because for most rows no test existed — the `auth`, `healtharea`, `reflection`,
  `reminder` and `user` contexts had none at all, no controller had a web-layer test, and no adapter had a
  persistence test. There was no rule saying those levels were expected, so nothing looked missing.
- Two invariants the requirements state as rules — BR-6 (one progress entry per upgrade per date) and
  BR-14 (concurrent edits are refused) — are enforced by a unique constraint and a `@Version` column.
  Both were "covered" by service tests that mock the repository, which assert the service's *intent* and
  say nothing about whether the constraint exists.
- Test names had drifted into two conventions. The frontend already used `Given…_When…_Then…`; the backend
  used `method_scenario_shouldResult`.

## Decision

**Four levels, and a rule for choosing.** Write a test at the *cheapest* level that can actually observe
the behaviour — and never at a level that cannot.

| Level | Runs in | May use | Owns |
|---|---|---|---|
| **Domain unit** | `mvn test` | Nothing. No Spring, no mocks. | Aggregates, value objects and domain services: state machines, invariants, calculations. `HealthUpgradeTest`, `StreakCalculatorTest`, `ProgressEvaluationServiceTest`. |
| **Application unit** | `mvn test` | Mockito, on **our own outbound ports only**. | Orchestration: that a service loads, calls the domain method, saves, publishes the right event, and refuses what it should refuse. |
| **Web slice** | `mvn test` | `@WebMvcTest` with the real `SecurityConfig` and `GlobalExceptionHandler`; the service below is mocked. | The HTTP contract: status codes, the authenticated boundary, request validation, and the JSON shape. |
| **Integration (`*IT`)** | `mvn verify` | The whole application on a Testcontainers PostgreSQL (ADR-008). | Everything the levels above structurally cannot see: bean wiring, Flyway migrations, database constraints, optimistic locking, and behaviour over a real socket. |

Alongside them sit the **structural tests**, which assert about the code rather than about a run:
`HexagonalArchitectureTest` (ArchUnit) and `FrontendEnumContractTest`. They are not a level — nothing
graduates to them.

The frontend has one level, by ADR-004: Vitest on jsdom, asserting behaviour through the rendered output
and the public hooks. It has no integration level, and this ADR does not add one.

Four rules follow from the table, and they are the point of it:

- **Mock only what we own.** A repository port may be mocked; PostgreSQL may not. If the assertion is
  about what the *database* does, the test is an `*IT` or it is not a test of that.
- **A slice test asserts the real security chain.** Disabling security to make a controller test pass
  deletes the only assertion that FR-5 has.
- **Assert through the public boundary** — the port, the endpoint, the rendered output. Never a private
  method, never an internal field. A test that pins an implementation detail blocks the refactor it was
  supposed to protect.
- **Deterministic, always.** Time comes from the injected `Clock` (NFR-15), never `Instant.now()`. No
  `sleep`, no real network, no dependence on test ordering.

**One naming convention: `Given<state>_When<action>_Then<outcome>`.** The existing backend suite is renamed
onto it in a single rename-only commit; the frontend already uses it.

**Coverage is reported and never gated.** JaCoCo and `@vitest/coverage-v8` publish a report on every CI
run; no threshold fails a build. The gate is `requirements.md`: every FR, BR and NFR names a test that
enforces it, or appears in §6 as an explained gap.

## Consequences

**What this makes easy**

- "Where does this test go?" has an answer that does not depend on who is asking, and "is this covered?"
  stops meaning "is there a percentage" and starts meaning "does a requirement name a test".
- The levels fail for different reasons, so a red build localises. A domain test failing means a rule
  changed; a slice test failing means the API changed; an `*IT` failing means the schema or the wiring did.
- Mocking a database becomes visibly wrong rather than merely unfortunate, which is what makes BR-6 and
  BR-14 get real tests.

**What this makes hard**

- **More tests per change.** A new endpoint now plausibly wants a domain test, a service test and a slice
  test. That is the intended cost; it is also a real one.
- **`*IT` is the slow level and the tempting one.** It can observe everything, so it will attract tests
  that belong higher up. "Cheapest level that can observe it" is a rule someone has to apply on review.
- **A rename touching every backend test file** is a large, if mechanical, diff — kept to its own commit so
  it can be read as one.
- **No coverage gate means no automatic backstop.** If nobody reads the report, coverage can rot silently.
  The requirements traceability is the real gate, and it is a human one.

**Neutral**

- No production code changes. Nothing here alters what the application does.

## Alternatives considered

- **A coverage threshold — fail the build under N%.** Mechanical, and needs no judgement. Rejected on the
  standing project principle that coverage is a diagnostic rather than a target: a threshold is satisfied
  by tests written to move a number, and it says nothing about whether BR-14 is enforced. Requirement
  traceability answers the question a percentage only appears to answer. **Revisit if** the traceability
  table is ever found to have quietly stopped being maintained — at which point a crude gate beats none.

- **Keep `method_scenario_shouldResult` and record it as this project's convention.** Defensible: the
  existing names are readable, the style is idiomatic Java, and renaming touches ~23 files while changing
  no behaviour. Rejected because the frontend already uses `Given…_When…_Then…`, so one of the two had to
  move, and a suite with two conventions teaches neither. **Revisit:** not worth revisiting; the cost is
  paid once.

- **Collapse the web slice into the integration level** — test controllers by driving the running
  application instead of `@WebMvcTest`. Fewer levels, and more realistic. Rejected because every such test
  then needs Docker and a full context boot, which pushes the HTTP contract out of `mvn test` and makes the
  fast suite blind to a status-code change. **Revisit if** slice tests are ever found passing while the
  real chain behaves differently — the one thing that would prove the slice is lying.

- **Mutation testing (PIT) to check the tests themselves.** The honest answer to "are these assertions
  real". Rejected as premature: the suite is still being built out, and PIT's run time on every push would
  dominate a pipeline whose current problem is missing tests, not weak ones. **Revisit when** coverage is
  broad and a defect still ships through a green build.
