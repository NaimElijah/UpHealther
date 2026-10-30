# ADR-076: Open-in-view is off

- **Status:** Accepted
- **Date:** 2026-10-31
- **Scope:**
  - `spring.jpa.open-in-view` in `backend/src/main/resources/application.yml`.
  - `ApplicationContextIT`, which pins it.
- **Issue:** [#340](https://github.com/NaimElijah/UpHealther/issues/340), scan 06 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D6.7. Changes one consequence of
  [ADR-028](ADR-028-a-raised-notification-is-stored-in-its-own-transaction-after-the-write-commits.md),
  and rests on [ADR-063](ADR-063-one-schema-with-cross-context-foreign-keys.md)'s plain-id entities.

## Context

Boot turns `open-in-view` on unless it is told otherwise. It registers an interceptor that opens a JPA
`EntityManager` when a web request arrives and closes it once the response has been written, so a view
can lazy-load an association after the service's transaction has ended. That `EntityManager` holds a
pooled connection from the request's first query to the end of the response.

ADR-028 counted on that. A notified write holds two connections at once, because its `AFTER_COMMIT`
listener opens a second transaction before the first lets go of its connection, and ADR-028 noted that
open-in-view held the first for the whole request anyway. With Hikari's default pool of ten, about ten
notified writes in flight could each hold one connection and wait for a second.

Nothing here needs what open-in-view is for. No entity maps an association: none has `@ManyToOne`,
`@OneToMany`, `@OneToOne`, `@ManyToMany` or `@ElementCollection`, each holds the ids of its neighbours
(ADR-063), and nothing calls `getReferenceById`. So nothing is ever loaded lazily outside a transaction.

## Decision

**`spring.jpa.open-in-view` is `false`.** A request's connection goes back to the pool when its last
transaction ends, not when its response has been written. `ApplicationContextIT` pins it: Boot registers
the open-in-view interceptor exactly when the property is on, and the test fails if the context holds one.

## Consequences

**What this makes easy**

- **A connection is held only while a transaction needs it.** Rendering a response, and a slow client
  reading it, hold none. A notified write still holds two connections while its listener runs, because
  the committed transaction lets go of its own only after that, but no longer for the rest of the request.
  ADR-028's arithmetic for exhausting the pool still holds; the window in which it can happen is shorter.

**What this makes hard**

- **Nothing can be lazy-loaded from the view.** An entity that gained a lazy association, read after its
  service returned, would throw `LazyInitializationException` rather than run a query. Today no entity maps
  one (ADR-063).

## Alternatives considered

- **Leave it at Boot's default.** Rejected: it holds a connection for the length of every response, to
  serve lazy loading that no entity here can do.
- **Raise the pool size instead.** It would ease ADR-028's arithmetic too. Rejected as the answer to this:
  it costs database connections to keep idle ones held, and the pool's size is
  [#220](https://github.com/NaimElijah/UpHealther/issues/220)'s decision, made from load, not this one.

## When to revisit

- **An entity maps an association**, a `@ManyToOne` or a collection, and a response needs it after the
  service returned. Then the choice is between fetching it inside the transaction and turning this back on,
  and fetching it inside is the usual answer.
