# ADR-040: No bug-pattern checker yet — Error Prone and SpotBugs found nothing new

- **Status:** Accepted
- **Date:** 2026-10-15
- **Scope:** `backend/pom.xml` — by leaving it as it is. No checker and no `.mvn/jvm.config`.
- **Issue:** [#162](https://github.com/NaimElijah/UpHealther/issues/162), step 2. Step 1 is
  [ADR-039](ADR-039-the-backend-compiles-under-javacs-lint-and-fails-on-a-warning.md).

## Context

ADR-039 makes the compiler fail on any lint warning. #162's second step asked whether a bug-pattern
checker should run as well. It named two candidates, and asked for them to be judged by what they find
here, not by reputation. "Neither yet" was a legitimate answer if both turned out noisy.

Both tools ran once over `backend/src` on 2026-10-15, after the palantir-java-format reformat
(ADR-037). Each ran on a scratch copy of the module, with the default settings and tests included:

| | Error Prone 2.50.0 | SpotBugs 4.10.5.0 |
|---|---|---|
| Findings | 98: 27 in `src/main`, 71 in `src/test`. 20 of the test findings are errors that stop compilation | 54: 45 in `src/main`, 9 in `src/test` |
| New defects among them | **0** | **0** |
| What the findings were | 19 are the entity `@PrePersist`/`@PreUpdate` timestamps read from the JVM zone. That is #51's known deviation, which `ServerTimeArchitectureTest` already fences off. 7 are Javadoc nits and 1 is `String.split`, documented as intended. In tests: 32 zone-less `now()` fixtures, 20 deliberate `catchThrowable(...)` calls in one class, and 19 style nits | 43 `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`, which is a record or a constructor-injected bean holding a `List` or an entity. It is how records and dependency injection work, not a leak. 1 `CT_CONSTRUCTOR_THROW` on a Spring bean. The null and unread-field findings are false positives, or tests passing `null` on purpose |
| Build time, clean compile of main and test | 57–77 s, against 31–36 s without it (two runs each; `CheckReturnValue` demoted to a warning so the test compile completes) | +51 s on top of the compile, as a goal of its own |
| Setup | Ten `--add-exports`/`--add-opens` lines in a new `.mvn/jvm.config`. `-XDaddTypeAnnotationsToSymbol=true` on JDK 21. Lombok moves into `annotationProcessorPaths`, because an explicit processor path turns off classpath discovery | A plugin and its goal. The engine is LGPL-2.1 and the Maven plugin Apache-2.0 |

## Decision

**Adopt neither tool now.** The backend is linted by javac, every category but `processing` and `serial`, with `-Werror` (ADR-039), and by the
architecture rules in `HexagonalArchitectureTest` and `ServerTimeArchitectureTest`.

Each tool was run against this codebase as it is, and neither found a defect that is not already
tracked:

- **Error Prone's real findings are #51.** An ArchUnit rule already fences that issue, and its own issue
  will fix it.
- **SpotBugs' findings are mostly the price of records and constructor injection.** Gating on SpotBugs
  would first mean a suppression file larger than the findings it keeps.

Adopting either would double the compile, or add a minute to `verify`. It would also add JVM flags or
a processor-path coupling that every later toolchain bump has to carry. The measured return is zero.

## Consequences

- **The build stays as fast as ADR-039 leaves it,** and `.mvn/` keeps only the wrapper.
- **Bug patterns are caught by review and tests, as before.** The lint covers what `javac` can see.
- **The two scratch runs are not kept.** Re-running either takes one command on a scratch copy, as this
  ADR's numbers were produced.

## Alternatives considered

- **Error Prone, at its default severities.** Rejected for now. Its 20 test errors and 78 warnings would
  all have to be fixed or switched off before `-Werror` let the build pass, and none of them is a new
  bug.
  - **Revisit:**
    - if a defect reaches `dev` that one of Error Prone's `ERROR`-level patterns would have caught;
    - when #51 routes the entity timestamps through the clock, which removes most of its main-code
      findings.
- **Error Prone with its `ERROR`-level checks only** (`-XepDisableAllWarnings`). Rejected for now. It
  would gate on the 20 `catchThrowable` calls alone. That is a deliberate idiom, so the gate would
  catch nothing on this codebase, at the same setup cost and the same doubled compile.
  - **Revisit:** as above.
- **SpotBugs, with `EI_EXPOSE_REP*` excluded.** Rejected. What remains is 11 findings, and none is a
  defect. The tool still adds about a minute to every `verify`, and its engine is LGPL.
  - **Revisit:** if a null or concurrency defect reaches `dev` that its detectors would have caught.

## When to revisit

- **A defect reaches `dev` or `main`** that either tool's default detectors flag.
- **A second regular contributor joins the backend.** Review then stops being the backstop, and the cost
  of a checker is shared.
- **Error Prone no longer needs the `jdk.compiler` exports.**
