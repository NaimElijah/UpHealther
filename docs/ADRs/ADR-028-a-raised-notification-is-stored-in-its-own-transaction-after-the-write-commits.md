# ADR-028: A raised notification is stored in its own transaction, after the write commits

- **Status:** Accepted
- **Date:** 2026-10-11
- **Scope:** the eight `AFTER_COMMIT` methods of `NotificationEventListener`, and FR-28, FR-32 and
  NFR-49 in `docs/requirements/requirements.md`.
- **Issue:** [#169](https://github.com/NaimElijah/UpHealther/issues/169)

## Context

A use case publishes a domain event inside its transaction, and `NotificationEventListener` turns
that event into a notification. The listener methods are
`@TransactionalEventListener(phase = AFTER_COMMIT)`, so a write that rolls back raises nothing. Each
method calls `NotificationService.create`, which is `@Transactional` with the default propagation,
`REQUIRED`.

That combination stored nothing. The push went out and the row did not:

- In Spring 6.1.6, the version this backend resolves, an `AFTER_COMMIT` listener is dispatched from
  `afterCompletion` (read from `TransactionalApplicationListenerSynchronization$PlatformSynchronization`
  in the jar). The committed transaction's `EntityManager` is still bound to the thread at that point,
  and the transaction manager still reports it as active.
- `create` therefore joined the committed transaction. Nothing commits that transaction again, so the
  insert was never flushed.
- `create` also registered its own after-commit callback to push the notification. That callback ran,
  so a connected browser got a notification whose row did not exist. The inbox (FR-33) never showed
  it, and an offline browser never heard about it at all.

Spring's javadoc for `TransactionSynchronization#afterCommit` warns about exactly this: data access at
that point still participates in the finished transaction, unless it runs as `PROPAGATION_REQUIRES_NEW`.

## Decision

**Each `AFTER_COMMIT` method of `NotificationEventListener` runs in a new transaction:
`@Transactional(propagation = Propagation.REQUIRES_NEW)`.**

- The new transaction stores the notification and commits it. `NotificationService.pushAfterCommit`
  then pushes once that commit is done, so NFR-49 holds as written.
- The annotation sits on the listener, not on `NotificationService.create`. The listener is the code
  that knows it runs after a commit. `create` stays `REQUIRED`: the scheduler, which calls it outside
  any transaction, still gets one per notification, and a future caller inside a transaction would
  still write atomically with it.
- `onOverdue` is left alone. It is a plain `@EventListener` for a sweep that has no transaction, and
  `createUnlessNotifiedSince` already opens one.
- Spring 6.1 refuses a `@TransactionalEventListener` that is also `@Transactional`, except with
  `REQUIRES_NEW` or `NOT_SUPPORTED` (`RestrictedTransactionalEventListenerFactory`). So a wrong value
  fails at startup, which `ApplicationContextIT` sees.
- `NotificationCommitIT` enforces the outcome. For each of the eight events it publishes through the
  real `DomainEventPublisher` and checks PostgreSQL afterwards: when the write commits, exactly one row
  is stored and pushed, and when it rolls back, nothing is stored or pushed.

## Consequences

- FR-32's "readable afterwards regardless" holds for the lifecycle notifications. NFR-49 now covers
  both halves: stored and pushed only after the write commits, and nothing for a write that rolls back.
- **A failed notification never fails the user's write.** The write has already committed when the
  listener runs. An exception from the listener is caught by
  `TransactionSynchronizationUtils.invokeAfterCompletion` and logged at ERROR
  ("TransactionSynchronization.afterCompletion threw exception"). The caller's response is not
  affected.
- **The other side of that: a notification can be lost.** If the second transaction fails, for
  example because the database dropped the connection between the two commits, the write stands and
  its notification does not exist. Nothing retries it, and the ERROR line is the only trace.
- Each notified write costs a second transaction, and with it a second pooled connection for its
  duration. That is negligible at this application's load.
- Nothing mechanical requires a new listener method to carry the annotation. A method added without
  it stores nothing, and only `NotificationCommitIT` would notice, once the method is in its
  parameters.

## Alternatives considered

- **`phase = BEFORE_COMMIT`.** The notification would commit atomically with the write, so it could
  never be lost, and `pushAfterCommit` would still defer the push. Rejected: a failure to create the
  notification would roll back the user's own write. Losing a goal the user saved, because a
  confirmation of it could not be written, is the worse failure.
- **`REQUIRES_NEW` on `NotificationService.create`.** It would fix the listener path by itself, and no
  caller today is inside a transaction it would break. Rejected: it moves the knowledge of an
  after-commit hazard into a service that does not run after a commit. It would also quietly split any
  future transactional caller's write from its notification.
- **A transactional outbox**: write the event into a table in the user's transaction, and have a relay
  turn it into a notification. That gives both atomicity and no loss. Rejected for now: a table, a
  relay and their failure handling, to protect an in-app message the user can do without.
- **A reflection or ArchUnit rule that every listener method is `AFTER_COMMIT`.** Rejected: all eight
  already were, so it could not have seen this bug. It pins a mechanism, not the outcome, and it needs
  an exemption for `onOverdue`. ADR-009 asks for a test at the cheapest level that can observe the
  behaviour, and an annotation does not show the behaviour.

## When to revisit

- **A notification starts driving something off the process**, such as email (#152) or Web Push
  (#157), where a lost notification means a lost message the user was promised. An outbox then earns
  its cost.
- **The backend runs as more than one instance.** The push then needs a broker anyway (see
  `architecture.md`, "Known constraints"), and moving the event through an outbox and that broker is
  one change.
- **The Spring Boot upgrade in #73** changes how transactional listeners are dispatched.
  `NotificationCommitIT` fails if storing breaks again.
