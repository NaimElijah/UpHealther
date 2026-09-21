# ADR-016: Two roles, read from the database on every request, beside ownership rather than instead of it

- **Status:** Accepted
- **Date:** 2026-09-20
- **Scope:** `backend/` — the `user` aggregate, the security adapter and the filter chain — and the
  `User` shape the SPA receives. A schema change (`V8`); no new dependency.

## Context

Authorization was ownership and nothing else. Every repository query is scoped by user id
(`findByIdAndUserId`, `findByUserIdAndStatus`, …), so one account cannot read another's rows, and
`SecurityUser.getAuthorities()` returned an empty list. That is a good model and it stays. What it
cannot express is the small set of things that are not about owning a row: listing the accounts on the
installation, switching one off, and granting somebody else the ability to do either.

There was also no way to stop an account. Deleting it destroys the records it owns, which is a
different decision with a different blast radius, and there was nothing in between.

Two constraints shaped the answer.

**A role that lives in the token is a role that cannot be revoked.** The access token is currently
valid for 24 hours. Putting `role` in its claims makes every request cheaper — no lookup — and makes
"remove this person's admin rights" mean "remove them within a day". The same argument applies to
disabling an account: a flag checked only at sign-in leaves a live token working until it lapses.

**An administrator is a privileged account, not a universal one.** The point of the ownership scoping
is that no one reads another person's health data. An admin role that could read everything would
undo the property the whole persistence layer is built to give, for the sake of a user-management
screen that does not need it.

## Decision

**Two roles, `USER` and `ADMIN`, on the `users` table, with no hierarchy.** `V8` adds
`role VARCHAR(16) NOT NULL DEFAULT 'USER'` with a `CHECK` constraint naming both values, and
`enabled BOOLEAN NOT NULL DEFAULT TRUE`. The defaults stay on the columns: registration writes neither
field, and an account that arrived without a role must be ordinary rather than unauthorised.

**Both are read from the row on every request.** `BearerTokenAuthenticator` already re-loads the
account to satisfy NFR-5; it now also refuses a disabled one, and `SecurityUser` derives its single
`ROLE_*` authority from the role it just read. Nothing about authorisation is carried in the token.

**An administrator gets extra paths, not extra rows.** `/api/admin/**` requires `hasRole('ADMIN')` in
the filter chain, and `@EnableMethodSecurity` lets the controllers say the same thing again where a
reader of the class will see it. No query is unscoped for an administrator, so an ADMIN reading health
data is not a rule that is enforced — it is a capability that does not exist.

**Neither field has a setter.** They move only through `User.changeRole`, `User.disable` and
`User.enable`, so the places an account gains a privilege or loses its access are countable.

**The enabled check moves after the password check.** Spring's `DaoAuthenticationProvider` runs its
pre-authentication checks before verifying the password, so a disabled account is refused without
BCrypt ever running — and that reply comes back fast enough to distinguish, across a network, from a
wrong password. `SecurityConfig` empties the pre-check and tests `enabled` in the post-check instead.
Both answers are 401 "Invalid credentials" and both cost one BCrypt comparison.

## Consequences

**Easy.** Revoking ADMIN or disabling an account takes effect on the next request, with no session
registry and nothing to invalidate. The SPA can hide an administration section it has no business
showing, knowing the server refuses the paths regardless of what the interface chose to render.
Disabling is reversible and destroys nothing, so "stop this account" no longer has to mean "delete
this person's records".

**Hard.** Every authenticated request now costs a user lookup — it already did, for NFR-5, so this
adds nothing, but it does mean the lookup can never be optimised away without re-opening both
revocation questions. The set of roles is written in two places, the Java enum and the `CHECK`
constraint, because Hibernate's `validate` checks a column's type and never its contents; they are
kept in step by hand. And the empty pre-authentication check means a future account flag — locked,
expired — will be silently unenforced unless it is added to the post-check.

## Alternatives considered

**A `role` claim in the access token.** Rejected: it makes revocation take up to the token's lifetime,
which is the one property this decision exists to have. **Revisit** when sessions are server-side and
the access token is short-lived (ADR-015): at a 15-minute TTL the window is small enough to trade for
the lookup, and if the user lookup ever shows up in a latency profile, this is the first thing to
reach for.

**An `ADMIN` that bypasses the user-scoped queries.** Rejected: it would make the ownership guarantee
conditional on a role, and the guarantee is worth more than the convenience. **Revisit** if support
ever genuinely needs to see another account's data — and then as an explicit, audited, separately
named capability, never as a side effect of holding a role.

**Spring Security's `hasAuthority` with fine-grained permissions** (`users:read`, `users:disable`, …).
Rejected as premature: there are four administrative operations and one administrator kind, so a
permission table would be indirection with nothing behind it. **Revisit** at the point a role needs to
hold some of the administrative operations and not others.

**A third role, or a hierarchy.** Rejected for the same reason. **Revisit** when a concrete second
kind of privileged account exists, with the operations it may and may not perform written down.

**Deleting an account instead of disabling it.** Rejected: deletion destroys the records the account
owns, which is a different decision with a different blast radius and its own requirements. Disabling
is the reversible, non-destructive answer to "stop this account now". Account deletion is tracked
separately in #57.
