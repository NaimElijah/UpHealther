# ADR-064: The backend's layering rules beyond ADR-002, their exceptions and their trade-offs

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `backend/` — the rules below in `HexagonalArchitectureTest`, and what they moved: the scheduled
    sweeps into `NotificationSweepService` and `UpgradeOverdueService`, the sweep reads into
    `UpgradeSweeps`, `ProgressSweeps` and `ReminderSweeps`, the bootstrap into `AdminBootstrapService`,
    and token issuing behind `AccessTokenIssuerPort`.
  - NFR-9 in `docs/requirements/requirements.md`, which counts the rules.
- **Issue:** [#338](https://github.com/NaimElijah/UpHealther/issues/338), scan 04 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334): findings A4.B3, A4.B4, A4.B6, A4.B8
  and A4.B11, and the review of [#378](https://github.com/NaimElijah/UpHealther/pull/378), findings 1 to
  7. It extends [ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md),
  whose "Enforce it" set out the first rules, and sits beside
  [ADR-062](ADR-062-the-shared-kernel-depends-on-no-bounded-context.md), which added the kernel's.

## Context

ADR-002 closed the gap between the layering the documents described and the one the build enforced. It
listed ten rules. Scan 04 found the same kind of gap again, in places those rules do not reach:

- **Driving adapters held use cases.** `NotificationScheduler` read the notification store and decided
  BR-23 itself, `UpgradeOverdueScheduler` published events, and `AdminBootstrapRunner` changed a role and
  audited it. A second trigger for the same work would have had nothing to call (A4.B4).
- **The core reached the kernel's adapters.** `AuthService` held `JwtTokenProvider`. The adapter rule
  keys on `..adapter..`, and most of `common`'s adapters live in packages not named that (A4.B3).
- **Ports named unpublished types.** `SessionCommand` and `AdminUserQuery` returned records from
  `application`, which the slice rule does not publish (A4.B8).
- **System-wide reads sat on the user-facing ports.** `findByStatus`, `findAllById`, `findEnabled` and
  `findUserIdsWithEntriesOn` returned every user's rows from the ports a request reaches (A4.B6).
- **Domain purity was a deny-list.** Any library it did not name was open to an entity (A4.B11).

The rules that closed these first cited ADR-002, which contains none of them. Their exceptions, and the
two trade-offs they accept, had reasons and revisit triggers only in test Javadoc. The review of #378
then showed several to protect less than their documents said: a deny-list claiming full coverage, a
pin bypassed one hop out, and a sweep port whose methods a controller could still call through the
concrete service it held.

## Decision

**`HexagonalArchitectureTest` enforces the rules below. Each exception is an origin and a target, named,
never a package let off.**

1. **A driving adapter triggers a use case; it does not perform one.**
   `inbound_adapters_do_not_drive_outbound_ports`: no class in `..adapter.in..` depends on a class in
   `..port.out..`, under `domain` or `application`. Its exceptions:
   - a composition adapter, to a port it implements and to the records those ports return
     (`AccountCredentials`, `UpgradeTrackingSummary`). Implementing a port is not driving one;
   - `SessionEndSocketListener` to `LiveSocketPort`. The decision to end the session is
     `AuthSessionService`'s, and the listener's one call carries it out (ADR-054);
   - `UpgradeWebMapper` to `UpgradeTrackingSummaryPort` and `UpgradeTrackingSummary`, the trade-off in
     point 6.

   The scheduled sweeps and the administrator bootstrap are application services the adapters call.
   `only_the_runner_bootstraps_an_administrator` keeps `AdminBootstrapService` to `AdminBootstrapRunner`,
   because its one guard, "no enabled administrator yet", would make any signed-in user the first
   administrator from behind an endpoint (ADR-016).

   The kernel's security adapters call the kernel's own inverted ports, `SessionStatusPort` and
   `AccountCredentialsPort`. That is by design, and they sit outside `..adapter.in..`.
2. **The core reaches the kernel only through `common.domain`.**
   `core_does_not_depend_on_the_kernels_adapters` is an allow-list: `..application..` and `..domain..`
   may depend on `com.healthupgrades.common..` only in `common.domain..`. A new kernel package is out of
   the core's reach from its first commit. Where the core needs what a kernel adapter does, it declares a
   port, as `AccessTokenIssuerPort` does for signing.
3. **A port names only published types.** `ports_name_only_published_types`: a class in
   `..application.port..` depends only on `java..`, other `..application.port..` packages,
   `..domain.model..` and `com.healthupgrades.common.domain..`. A cross-context caller of a port can
   then import whatever it returns, without widening the slice rule.
4. **Every user's rows are read through sweep ports, held only by the jobs.**
   `sweep_ports_serve_only_scheduled_work`: a `*SweepQuery` port has two kinds of dependent:
   `NotificationSweepService` and `UpgradeOverdueService`, and the class implementing it. Each port is
   implemented by a class of its own, `UpgradeSweeps`, `ProgressSweeps` or `ReminderSweeps`. On the
   service a controller holds, the sweep would be one call away from a request handler, past the rule.
5. **The domain depends only on what it uses.** `domain_depends_only_on_what_it_needs` allows
   `java.lang`, `java.util`, `java.time`, `java.security`, `java.nio.charset`, `jakarta.persistence`,
   `lombok` and this codebase. `domain_uses_jpa_only_to_map` bans JPA's runtime types, `EntityManager`
   and the queries among them, so `jakarta.persistence` means the mapping annotations ADR-001 accepts.
   The deny-list rule beside them stays, for its clearer message on the common frameworks.
6. **Two trade-offs are accepted, and pinned.**
   - **`TrackingConfigQuery.findByUpgradeIds` is not scoped by owner.** `tracking_configs` has no owner
     column. Its one caller, `UpgradeTrackingSummaryAdapter`, passes it through to
     `UpgradeTrackingSummaryPort`, and that port's one user, `UpgradeWebMapper`, passes only the ids of
     upgrades already loaded through user-scoped reads. Two rules pin both hops:
     `unscoped_tracking_config_read_stays_inside_tracking` and
     `tracking_summary_port_serves_only_the_upgrade_mapper`.
   - **A web mapper does an outbound read.** `UpgradeWebMapper` resolves each upgrade's tracking setup
     through `UpgradeTrackingSummaryPort` while it maps, after the use case's transaction has ended. The
     alternative changes about a dozen use-case signatures, and `architecture.md` names the cost.

## Consequences

**What this makes easy**

- **A failing rule leads to its reason.** Each rule's message cites this record, and this record names
  every exception and its revisit trigger.
- **A second trigger has a use case to call.** An endpoint beside a cron, or a CLI beside the bootstrap,
  calls the same application method, and the rules stay where they are.
- **A new package, library or kernel adapter is out until someone decides otherwise.** The allow-lists
  fail the build on its first use.

**What this makes hard**

- **More classes for the same work.** A sweep read is a port and a class of its own, the bootstrap is a
  service of its own, and token issuing is a port and an adapter.
- **Every new need is an edit to the test.** A domain class that wants another JDK package, or a port
  that wants an annotation, changes an allow-list, with a reason in its Javadoc.
- **The exceptions are names in a test.** Renaming `UpgradeWebMapper` or `SessionEndSocketListener`
  breaks a rule until the name is changed there too.

## Alternatives considered

- **Keep citing ADR-002.** Rejected. It records none of these rules, so a reader following the link from
  a failing rule finds no reason and no trigger, and the easy fix is to widen the rule.
- **Deny-lists, extended as packages appear.** Rejected. Each list is complete only until the next
  package, as `core_does_not_depend_on_the_kernels_adapters` was. An allow-list fails on the first use
  of anything new.
- **Exceptions by package.** Rejected. Letting off `..adapter.in.composition..` for all of
  `common.domain.port.out` also let a composition adapter publish events and write audit entries.
- **Scope `TrackingConfigQuery` now.** Rejected: a migration adding an owner column, a join onto
  `upgrade`'s table, or a query per response, to close a path no request reaches. The two pins hold the
  path closed instead.
- **Move the tracking setup into the use cases.** Rejected: about a dozen signatures, ten
  `UpgradeService` methods and the dashboard's view, to remove one read the mapper does.
- **Leave the sweep methods on the services, and ban calls to them by name.** Rejected. A rule on method
  names breaks on a rename, and the method would still sit on a class every controller holds.

## When to revisit

- **`TrackingConfigQuery` is scoped first** if a class outside `tracking` needs it, if a class other
  than `UpgradeWebMapper` needs `UpgradeTrackingSummaryPort`, if an id from a request reaches either, or
  if `tracking_configs` gains an owner column.
- **The mapper's read moves into the use cases** if a second web mapper needs it, if a response must show
  the upgrade and its setup as one snapshot, or if a use case needs the setup itself.
- **A third named exception to `inbound_adapters_do_not_drive_outbound_ports`** is a sign the adapter
  kinds are wrong, not that the rule is.
- **The domain needs a JDK package or library it has never used.** That is a decision for its own
  record, or a line here, before the allow-list grows.
