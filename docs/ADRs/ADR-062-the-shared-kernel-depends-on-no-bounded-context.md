# ADR-062: The shared kernel depends on no bounded context

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `backend/` — `SecurityUser`, `BearerTokenAuthenticator` and `UserDetailsServiceImpl` in
    `common.security`; the new `AccountCredentialsPort` and `AccountCredentials` in
    `common.domain.port.out`; the new `AccountCredentialsAdapter` in `user.adapter.in.composition`; and
    the new rule `shared_kernel_depends_on_no_bounded_context` in `HexagonalArchitectureTest`.
  - No change to the API, the wire types, the schema or the tokens. No new dependency.
- **Issue:** [#338](https://github.com/NaimElijah/UpHealther/issues/338), scan 04 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding A4.B2. It extends
  [ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md), which inverted
  the `upgrade`/`tracking` edge and set out the ArchUnit rules, and applies to the kernel the inversion
  [ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md) made for
  `SessionStatusPort`.

## Context

Every bounded context depends on `common`. Until this change, `common` also depended on one of them:

- `SecurityUser` imported `user`'s `User` and `Role`.
- `BearerTokenAuthenticator` and `UserDetailsServiceImpl` loaded accounts through `user`'s inbound port
  `UserQuery`, and `UserDetailsServiceImpl` normalised the address with `user`'s `EmailAddress`.

A rule written to forbid this found 17 dependencies, all in those three classes.

The project had already decided against this kind of edge twice:

- ADR-015 declared `SessionStatusPort` in `common` and had `auth` supply it, because the security
  adapter lives in the shared kernel and "a direct call would point the kernel at a bounded context".
- ADR-054 inverted `LiveSocketPort` for the same reason, because `common → auth` would be a cycle.

Nothing enforced that reasoning. The cycle rule did not catch the edge to `user`, because it is not a
cycle: `user` imported nothing from `common`. It does catch the other direction, though, and that
was a cost already being paid:

- `EmailAlreadyRegisteredException` said it could not reuse `common`'s `BusinessRuleException`,
  because "`common` already depends on `user`, so the reverse import would be a cycle".
- `user` was held off the kernel every other context uses. It could not take an audit trail, a domain
  event or a shared exception from it.

Two documents described the system wrongly as a result. The diagram README said every context depends
on `common` "by definition", which was false for `user`. `UserQuery`'s doc comment named the security
adapter among its callers. That was true, and it was the edge.

## Decision

**`common` depends on no class in any bounded context, and `HexagonalArchitectureTest` fails the build
otherwise.**

1. **The rule is an allow-list.** A class in `com.healthupgrades.common..` may depend on
   `com.healthupgrades` classes only inside `common`. A context added later is out of the kernel's
   reach from its first commit. It has the same shape as the `admin` rule's allow-list (ADR-016).
2. **The kernel declares what it needs as a port, and `user` supplies it.**
   - `AccountCredentialsPort`, in `common.domain.port.out`, loads an account by id (the token's `sub`)
     and by address (a sign-in). It returns `AccountCredentials`, a kernel-owned record holding the id,
     the address, the password hash, the role's name and the enabled flag. That is what `SecurityUser`
     needs and nothing more.
   - `user.adapter.in.composition.AccountCredentialsAdapter` implements it over `UserQuery`, as
     `auth.adapter.in.composition.SessionStatusAdapter` implements `SessionStatusPort`.
   - It is named for what the kernel needs, not for the context that answers.
3. **The role crosses the port as its name.** `SecurityUser` makes its one authority as
   `"ROLE_" + name`, exactly as it did from `Role.name()`. So `hasRole('ADMIN')` in `SecurityConfig` and
   on `AdminUserController` match as before, and a role added to `Role` later would too.
   `SecurityUser.getRole()` is removed, because it returned `user`'s `Role` and nothing called it.
4. **The address is normalised on the `user` side.** `EmailAddress` is `user`'s rule for when two
   addresses are one account. The kernel passes the address on as typed, and the adapter normalises it
   before the lookup. Every sign-in reaches the lookup through that adapter, so none can skip it, which
   is the guarantee `UserDetailsServiceImpl` gave before.
5. **The record does not print its credentials.** Its `toString` omits the address and the hash, which
   a record's generated one would print (NFR-6).

## Consequences

**What this makes easy**

- **`user` may use the kernel.** An audit trail, a domain event or a kernel exception no longer closes
  a cycle. `EmailAlreadyRegisteredException` stays as it is: registration catches that one refusal
  around the save, and a `BusinessRuleException` would let it catch more.
- **The kernel can be reasoned about alone.** Nothing in `common` changes when a context's model
  changes, unless that context's adapter has to change what it supplies.
- **The documents can say true things and be checked.** "`common` depends on no context" is a rule the
  build enforces, not a claim.

**What this makes hard**

- **One more port, record and adapter.** It is the third of its kind, after `SessionStatusPort` and
  `UpgradeTrackingSummaryPort`.
- **A test of the security adapter stubs the port.** `AUser.credentialsFor` repeats the adapter's
  translation for those tests. `AccountCredentialsAdapterTest` pins the adapter's own translation, so
  a drift between the two fails one of them.
- **The role is a `String` inside the kernel.** The kernel cannot refuse a malformed name. Its one
  producer is the adapter, and `AccountCredentialsAdapterTest` checks that every `Role` arrives under its
  own name.
- **Each new need of the kernel is a new port.** The kernel cannot reach into a context for one more
  field.

## Alternatives considered

- **Accept the edge, with an ADR and an exception pinned in the rule.** An ignore for
  `common.security → user`, and a sentence saying why. Rejected:
  - it keeps `user` the one context that cannot use the kernel, the cost that was already being paid;
  - one sanctioned exception is a precedent for the next one;
  - the inversion costs one interface, one record and one class. ADR-015 and ADR-054 already paid that
    twice, for the same reason.
- **Move `User` and `Role` into the kernel.** Rejected. `User` is `user`'s aggregate: a JPA entity whose
  role and enabled flag change only through its own methods. In the kernel, every context would see
  the entity and its persistence mapping. The kernel would then own a context's model, which is the
  same coupling pointing the other way. `UserQuery` and `EmailAddress` would have to follow it, or the
  edge would remain.
- **A kernel-owned role enum.** Rejected. It would be a second copy of `Role` in Java, kept in step by
  a mapping. The set is already written twice, once as the enum and once in `V8`'s check constraint. It
  buys a compile-time check of a match that `hasRole` makes by name anyway.
- **Carry Spring's `GrantedAuthority` across the port.** Rejected. The port lives in `common.domain`,
  which ArchUnit keeps free of Spring, and `user` would build security types for the kernel.
- **Move the security adapter into `auth`.** Rejected. Controllers in every context take a
  `SecurityUser`, so every context would then depend on `auth`. The HTTP filter and the STOMP
  interceptor both authenticate through `BearerTokenAuthenticator`, and both are cross-cutting.

## When to revisit

- **The kernel needs a fourth thing only a context knows.** By then, security may belong in a context
  of its own, one that the others depend on, rather than in the kernel with ports out of it.
- **Authorisation needs more than one role's name.** A permission model, or a second authority per
  account, would mean `roleName` is the wrong shape. That change supersedes ADR-016 as well as this
  record.
- **A context is split into a deployable of its own.** The port then becomes a remote call on every
  request, and NFR-5's "re-read on every request" needs a decision of its own.
