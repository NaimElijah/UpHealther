# ADR-026: A scheduled run that threw is logged in full, like an unexpected 5xx

- **Status:** Accepted — extended by
  [ADR-027](ADR-027-hibernates-sql-exception-logger-is-turned-off.md), which turns off the Hibernate
  logger the third consequence below leaves as NFR-6's known deviation
- **Date:** 2026-10-08
- **Scope:** NFR-6 in `docs/requirements/requirements.md`. No code changes. Extends
  [ADR-010](ADR-010-structured-logging-and-a-level-policy.md), and answers the question
  [ADR-023](ADR-023-a-notification-sweep-carries-on-past-a-failed-save-and-fails-the-run.md) left open.
- **Issue:** [#139](https://github.com/NaimElijah/UpHealther/issues/139), found in the review of #138

## Context

NFR-6 says the application never logs personal data deliberately. It made one exception: "the stack
trace of an unexpected 5xx, logged in full so the fault is diagnosable and withheld from the client".

A `@Scheduled` run that throws is logged in full too, message included, and it is not a 5xx:
- `JobMetrics.timed` counts the run `failed` and rethrows on purpose.
- Spring's scheduler then hands the throwable to its default error handler. In `spring-context` 6.1.6,
  the version this backend resolves, a repeating task gets `TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER`.
  Its `handleError` calls `log.error("Unexpected error occurred in scheduled task", t)`, read from the
  jar's bytecode. Logback then writes the throwable's message, its cause chain and its stack trace.
- [ADR-010](ADR-010-structured-logging-and-a-level-policy.md) puts "a `@Scheduled` run that threw" at
  ERROR, and `JobMetrics`' class comment relies on Spring writing that stack trace. So the stack trace
  is intended.
- The message can carry what a user typed. A PostgreSQL constraint violation reads
  `Failing row contains (…)`, and the row can hold an upgrade's title.

[ADR-023](ADR-023-a-notification-sweep-carries-on-past-a-failed-save-and-fails-the-run.md) made this
visible. Its per-item WARN leaves the message out for NFR-6's sake, while the run's single ERROR line,
for the first failure, still carries it.

**Not reproduced.** Nobody has made a scheduled insert hit a constraint to capture the line. The
handler's behaviour is read from the bytecode, and the message's content is #139's example.

## Decision

**NFR-6's exception covers an unexpected fault, not only a 5xx: a scheduled run that threw is logged in
full, message included, exactly as an unexpected 5xx is.** The owner chose this on #139.

- **The run's one ERROR line keeps its message.** The message is usually the diagnosis: which
  constraint, which statement, which timeout. It is the same trade the 5xx already makes. The fault
  becomes diagnosable, and the line sits at ERROR, where somebody is about to look anyway.
- **Per-item lines still carry types only.** ADR-023's WARN names the ids, the exception's type and its
  root cause's type, never a message. It repeats once per failed item, and it is not the line that
  reports the fault, so this decision does not reach it.
- **Code never re-logs a fault's message itself.** The exception is for the framework's single line
  for a fault, not for application statements. `backend/CLAUDE.md` states this beside the rest of
  NFR-6.

## Consequences

- NFR-6 now describes what the application does. Before, the requirement and the behaviour disagreed.
- A driver's message, which can quote a refused row, can reach the log: once per failed run, at ERROR
  only. That is the exposure the 5xx exception already accepted. The log stays with the operator, kept
  for as long as `docker logs` keeps it, the retention ADR-011 accepted for the audit stream.
- **This does not cover Hibernate's own line.** `SqlExceptionHelper` writes the driver's message at
  ERROR on every SQL failure, handled or not, separately from the exception. It predates this decision,
  falls outside it, and is NFR-6's known deviation,
  [#142](https://github.com/NaimElijah/UpHealther/issues/142).
- Nothing changes in code, so no test changes. `NotificationSchedulerTest` keeps pinning the part of
  NFR-6 that code enforces: the per-item WARN carries no message.

## Alternatives considered

- **A scheduler `ErrorHandler` that logs the type and stack frames without the message.** Rejected: it
  hides the driver's own explanation of the failure from the person paged for it. It would also apply
  to every job, and Boot's scheduler would have to be customised to install it.
- **Rethrow a wrapper that keeps the stack trace and drops the text.** Rejected for the same reason. It
  also changes what `JobMetrics` promises to rethrow, which is the job's failure, untouched.
- **Narrow the exception to the notification sweeps.** Rejected on #139 before it was decided: the
  other jobs can hit the same driver messages.

## When to revisit

- **Logs leave the operator's control.** For example, logs shipped to a third-party aggregator, retained
  beyond `docker logs`, or readable by people who must not see user data. Then a message that can quote
  a row has to be stripped for both the 5xx and the scheduled run, with whichever handler does that.
- **A compliance obligation** applies to what logs may contain.
- **A job starts failing often enough** that the ERROR line is routine rather than exceptional. The
  exposure then grows with the failure rate, and the fault needs fixing anyway.
