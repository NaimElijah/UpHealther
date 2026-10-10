# ADR-027: Hibernate's SQL exception logger is turned off

- **Status:** Accepted
- **Date:** 2026-10-10
- **Scope:** `logging.level` in `backend/src/main/resources/application.yml`, and NFR-6 in
  `docs/requirements/requirements.md`. Extends [ADR-010](ADR-010-structured-logging-and-a-level-policy.md)
  and closes the gap [ADR-026](ADR-026-a-scheduled-run-that-threw-is-logged-in-full.md) left open.
- **Issue:** [#142](https://github.com/NaimElijah/UpHealther/issues/142), found in the review of #141

## Context

Hibernate's `org.hibernate.engine.jdbc.spi.SqlExceptionHelper` logs every SQL failure itself, before
the exception reaches anything that handles it. In `hibernate-core` 6.4.4.Final, the version this
backend resolves, `logExceptions` writes two lines (read from the jar's bytecode):

- WARN: `SQL Error: <code>, SQLState: <state>`.
- ERROR: the driver's message. A PostgreSQL message quotes the row. A duplicate registration writes
  `Key (email)=(…) already exists`, and a check-constraint failure writes `Failing row contains (…)`,
  which is the whole `users` row, password hash included.

Nothing configured that logger, so it ran at Spring's default INFO. The result broke two things:

- **NFR-6.** A refused registration is handled and answered with a 422, yet the email address landed in
  the log at ERROR. ADR-026's exception covers the one line that reports an unexpected fault. This line
  is not that line, and it is not confined to a fault.
- **ADR-010's level policy.** ERROR means somebody must act now. A duplicate registration, or a
  duplicate progress entry under BR-6's `uq_progress_upgrade_date`, is a refusal the application
  answers on purpose. Nobody has to act.

The diagnosis does not need this line. `GlobalExceptionHandler` logs an unexpected 5xx with its cause
chain, and Spring's scheduler logs a run that threw (ADR-026). The driver's message is in both,
because the `JDBCException` carries the `SQLException` as its cause.

## Decision

**`org.hibernate.engine.jdbc.spi.SqlExceptionHelper` is set to `OFF` in `application.yml`.**

- A handled refusal now writes nothing. The audit trail still records it as `outcome=REFUSED`.
- An unexpected fault is still logged once, in full, by the line ADR-026 describes.
- The value is quoted, `"OFF"`, because YAML 1.1 reads a bare `OFF` as boolean `false`.
- `RegistrationRaceIT` enforces it. That test captures the root logger while the database refuses a
  duplicate address, and fails if any line, or any message in a logged cause chain, carries the address.
  It captures the root logger rather than this one, so another framework logger that starts quoting
  rows fails it too.

## Consequences

- NFR-6 holds for SQL failures. Its known deviation is removed.
- **Lost: the WARN line with the SQLState**, which said a failure happened even when the application
  handled it. A refusal is still visible where it matters: as an audit entry, and as a 4xx in
  `http.server.requests`.
- **Lost: JDBC `SQLWarning`s.** The same logger writes them, at WARN, when Hibernate is set to collect
  them, and this setting silences them too. None are relied on today.
- Turning the logger back on, for example to debug locally with `--logging.level...=INFO`, brings the
  personal data back. That is acceptable for one local run and never for a deployment.

## Alternatives considered

- **pgjdbc's `logServerErrorDetail=false` on the JDBC URL.** Rejected. It strips the server's detail
  from every driver message, so it would also strip `Failing row contains (…)` from the 5xx stack trace
  and the scheduled run's ERROR line. That reopens the trade-off ADR-026 settled in the other direction.
  It also leaves a handled refusal logged at ERROR. Not verified against pgjdbc 42.6.2, the version this
  build resolves.
- **Both: this setting and `logServerErrorDetail=false`.** Rejected for now. The second half is only
  needed once logs leave the operator's control, which is ADR-026's own trigger.
- **Lower the logger to WARN instead of turning it off.** Rejected. It drops the ERROR line with the
  message and keeps the SQLState line, so a handled refusal would still log a WARN, which ADR-010
  reserves for "degraded, still serving". The SQLState alone is not the diagnosis anyway: the 5xx stack
  trace carries it.
- **Translate each constraint violation before Hibernate logs it.** Not possible. The helper logs when
  it converts the `SQLException`, before any application code sees the failure.

## When to revisit

- **Logs leave the operator's control.** ADR-026's trigger: the driver's message in the 5xx and
  scheduled-run lines must then be stripped as well, and `logServerErrorDetail=false` becomes the
  candidate.
- **A database warning needs to be seen.** For example, a PostgreSQL `NOTICE` that a migration or a
  query relies on. Then route `SQLWarning`s somewhere that does not also carry the exception messages.
- **The Hibernate upgrade in #73** moves or renames this logger. `RegistrationRaceIT` fails if it does.
