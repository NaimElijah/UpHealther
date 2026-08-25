# ADR-011: The audit trail is a log stream, not a table

- **Status:** Accepted
- **Date:** 2026-08-25
- **Scope:** `backend/` — one outbound port, one adapter, and a recording call in every state-changing
  use case. No new dependency. No migration. No change to any aggregate.

## Context

Nothing in this application recorded who did what. There is no `@EnableJpaAuditing`, no Envers, and no
audit table in any of the five migrations. The seven entities carry `createdAt` and `updatedAt`, written
by `@PrePersist`, and those are row state: they say when a row last changed and never who changed it,
what it changed from, or that somebody tried something and was told no.

The last of those matters most and is the easiest to miss. BR-15 reports another user's record as
*absent* rather than forbidden, so an attempt to reach into somebody else's data is answered `404` and
leaves no trace anywhere. The same was true of every refused transition, every duplicate progress entry
and every rejected credential. The system's refusals were invisible, which is precisely the population a
security review or an incident reconstruction is interested in.

[ADR-010](ADR-010-structured-logging-and-a-level-policy.md) settled how log lines are formatted. This
one settles what is written when state changes, and where it goes.

## Decision

**Audit entries are a structured log stream.** `AuditTrail` is an outbound port in
`common/domain/port/out/`, beside `DomainEventPublisher` and for the same reason — every context records
through it, and one trail is more useful than nine. `LoggingAuditTrail` writes to a logger named `AUDIT`
at INFO through `StructuredArguments`, so one statement renders as `key=value` for a person reading the
console and as first-class JSON fields for anything querying it.

**A log stream rather than an `audit_log` table, and the reason is not effort.** An audit entry has to
survive the transaction it observes. A refused transition rolls back, and a row written inside that
transaction rolls back with it — losing exactly the entry somebody was looking for. Recording it outside
the transaction instead means a second connection and a second failure mode, to store a record this
application has no read path for and no requirement to query. The entry also arrives already correlated,
because the trace id is on the line, so it leads back to the request that produced it without a foreign
key. And with no table there is no retention policy to write and no erasure obligation to honour when an
account is deleted.

**An audit event has nowhere to put personal data.** `AuditEvent` is
`(AuditAction, actorUserId, resourceId, AuditOutcome)` — two enums and two identifiers. NFR-6 says the
application does not log personal data; this makes that a property of the type rather than of whoever
reviews the next call site. An upgrade's title, a reflection's body and an email address cannot be
audited because they cannot be represented, and `AuditEventTest` asserts it by inspecting the record's
components, so a future `String` field fails at the moment somebody adds it.

**An `ALLOWED` entry waits for the commit; a refusal does not.** Every audited use case is an
`@Transactional` service method and the recording happens inside the body, so the work returns while the
transaction is still open — the commit happens afterwards, in the proxy. No repository adapter flushes,
so a `@Version` clash or a unique constraint losing a race is decided *at commit*, after the body has
returned. Writing `ALLOWED` at that point would have the trail and the counter both report an edit that
was then rolled back and answered to the caller as a 409. `LoggingAuditTrail` therefore defers the
`ALLOWED` entry through the same `afterCommit` route `NotificationService` already uses, and records the
attempt as `REFUSED` if the transaction rolls back instead — the reason is not knowable at that point,
and the likely causes are the database declining the write, so calling every one of them `FAILED` would
drown the signal that outcome exists to raise. Refusals and faults are written immediately: they already
describe an attempt that did not land, they are the security-relevant half, and a process that dies
before commit should not take them with it. The deferral lives in the adapter because
`TransactionSynchronizationManager` is Spring, and ArchUnit keeps the domain free of it.

**Refusals are recorded, and the wrapper is what guarantees it.** `AuditTrail.recording(...)` runs the
operation, records `ALLOWED` on return and the classified failure on the way out, and rethrows untouched.
It is a default method on the port rather than a try/catch at each call site because a trail that records
only successes is not a trail, and "record the failure too" written twenty-one times is written twenty.

**Three outcomes, not two.** `REFUSED` is the system working — a rule, an ownership check or a conflict
said no. `FAILED` is the system broken. Collapsing them would make the one alerting signal worth having
indistinguishable from ordinary traffic. `AuditOutcome.of` classifies by the four exceptions in
`common.domain.exception`, which are this application's vocabulary for "no". `AuthService` classifies its
own, because a rejected credential is Spring Security's exception and would otherwise be counted a fault —
the same distinction `GlobalExceptionHandler` already draws when it refuses to report a database outage
to a user as a wrong password.

**A refused login is recorded with no subject at all.** No actor, no resource. The submitted email is
personal data, an IP address is personal data, and naming a user id would confirm the account exists —
which is what the 401 handler deliberately refuses to do on the wire. What survives is a count and a
trace id.

**Notifications are not audited.** They are raised by schedulers and event listeners rather than by a
person, and `dispatchReminders` alone runs every minute; auditing them would record the system talking to
itself, at volume, under the heading of who did what. Marking one read is a display flag, not a change to
the user's record.

**The counter is derived in the adapter from the same call.** `audit.events{action,outcome}` is
incremented where the line is written, because the event already carries exactly the bounded-cardinality
labels a counter wants, and deriving it there means the count and the trail cannot disagree. Neither the
actor nor the resource is a tag: a user id as a label is an unbounded cardinality, and a metrics backend
is the wrong place to look one up.

## Consequences

**Easier.** "Who changed this, and what were they refused?" is answerable, in the same query language as
everything else in the log, joined to the request by the same trace id. Adding an operation to the trail
is a constant in `AuditAction` and a wrapper around a body. Nothing about the schema, the migrations or
the aggregates changed, so nothing about them can have broken.

**Harder.** An `ALLOWED` entry is now written from a transaction callback rather than from the call
that produced it, so the code that decides *what* is recorded and the code that decides *when* sit in
two different files — `AuditCommitIT` exists to keep that seam honest, and it fails if the deferral is
removed. The trail's retention is the log's retention, and today that is whatever `docker logs`
keeps — which is not a compliance story, and is written here so nobody assumes it is one. `AuditAction`
is a file in `common` that a context edits when it adds an operation, a coupling accepted so that the
whole vocabulary can be read in one place. And twenty-one use-case bodies now sit inside a lambda, so a
local that was reassigned had to be split in two.

**A refused login is a rate signal, not an attribution.** It is deliberately impossible to tell from the
trail *whose* account was targeted, so as written it will not support a lockout policy or an "unusual
activity" alert. *Revisit when* rate limiting or lockout is implemented — at that point an identifier
becomes necessary, and choosing one (a hash of the email, a truncated IP) is a privacy decision that
deserves its own record rather than being smuggled in as a code change.

## Alternatives considered

- **An `audit_log` table written through a repository port.** Durable independently of log retention and
  queryable in SQL, which is the real argument for it. Rejected for now: it rolls back with the refusal
  it was recording unless it gets its own transaction, it needs a retention policy and an erasure path on
  account deletion, and it stores something with no read path and no requirement behind it. *Revisit
  when* a requirement appears that the log cannot meet — a compliance obligation, a user-visible history,
  or anything that has to be queried transactionally alongside the data it describes.
- **Hibernate Envers.** Full row-level history for nothing but an annotation, and genuinely the right
  tool for "what did this record look like last Tuesday". Rejected because it answers a different
  question: Envers records versions of rows, not attempts by people, and it cannot record the refusals —
  which never reach the database — that are the reason this trail exists. It also doubles the schema.
- **Auditing from a domain-event listener.** Almost free, since the events already exist and already
  carry the user id. Rejected because domain events are published *after* a successful change, so the
  entire refused population would be missing, and `backend/CLAUDE.md` already rules out per-event
  listeners that exist only to write a line.
- **Auditing in `GlobalExceptionHandler`, where every refusal already converges.** Tempting: one place,
  total coverage of failures, zero call sites. Rejected because it only knows the HTTP request, so
  entries would be keyed by method and URL — a vocabulary that changes whenever a route does — and it
  would record no successes at all, leaving the trail split across two incompatible schemes.
- **An AOP aspect over the application layer.** Rejected on the grounds ADR-007 rejected `@Observed` for
  the scheduled jobs: a new starter and proxies around every service, and an aspect still cannot name the
  resource id without an annotation on each method — which is the call site again, with indirection added.
