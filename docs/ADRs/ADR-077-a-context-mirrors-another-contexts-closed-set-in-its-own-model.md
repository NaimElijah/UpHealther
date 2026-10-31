# ADR-077: A context mirrors another context's closed set in its own domain model

- **Status:** Accepted
- **Date:** 2026-10-31
- **Scope:**
  - `UpgradeTrackingType` and `UpgradeTrackingFrequency` in
    `backend/src/main/java/com/healthupgrades/upgrade/domain/model/`.
  - `UpgradeTrackingSummary`, `UpgradeTrackingConfigDto` and `UpgradeTrackingSummaryAdapter`, which carry
    and fill them.
  - `UpgradeTrackingEnumMirrorContractTest`, which holds them equal to `tracking`'s enums.
- **Issue:** [#341](https://github.com/NaimElijah/UpHealther/issues/341), scan 07 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding A7.5. Supersedes in part
  [ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md), whose summary
  carried these values as their names. Builds on ADR-002's inverted `UpgradeTrackingSummaryPort`,
  [ADR-001](ADR-001-ddd-hexagonal-architecture.md)'s package layout,
  [ADR-053](ADR-053-the-frontends-wire-types-are-generated-from-the-apis-records.md)'s generated wire
  types and [ADR-064](ADR-064-the-backends-layering-rules-beyond-adr-002.md)'s layering rules.

## Context

An upgrade's response embeds its tracking configuration. `upgrade` cannot read it from `tracking`
directly, because `tracking` already depends on `upgrade` for ownership checks and
`bounded_contexts_are_free_of_cycles` refuses a cycle, so `upgrade` declares `UpgradeTrackingSummaryPort`
and `tracking` fills it in (ADR-002). The summary record the port returns may not name `tracking`'s
types either, or the dependency the port removes would be back.

So the summary carried the tracking type and the frequency as their names, `String`s, and
`UpgradeTrackingConfigDto` did too, which ADR-002 accepted as the cost of the inversion. The frontend's
types are generated from the response records (ADR-053), and a `String` is rendered as `string`:
`trackingConfig?.trackingType === 'NUMERC'` compiled, and a constant renamed in `tracking` would reach no
`tsc` error in the SPA. NFR-10 requires that the enums a wire type carries cannot drift. The standalone
`TrackingConfigDto`, in `tracking` itself, has always carried the unions.

The wire-type generator keys types by simple name, so a second enum called `TrackingType` would collide
with `tracking`'s.

## Decision

**`upgrade` restates the two closed sets as enums of its own, `UpgradeTrackingType` and
`UpgradeTrackingFrequency`, in `upgrade.domain.model`, and a contract test holds each equal to the
`tracking` enum it mirrors.**

- **`domain.model` is where ADR-001's package table puts a context's enums**, and where `UpgradeDto`'s
  other enums, `Difficulty`, `UpgradeStatus` and `UpgradeType`, already live. Every rule in
  `HexagonalArchitectureTest` passes there unchanged:
  - the web DTO already depends on `upgrade.domain.model`;
  - `ports_name_only_published_types` admits `..domain.model..`, so the summary in `port.out` may name them;
  - the slice rule publishes another context's `..domain.model..`, so `tracking`'s composition adapter
    may reach them;
  - a bare enum depends on nothing but `java.lang`.
- `UpgradeTrackingSummary` and `UpgradeTrackingConfigDto` are typed by the mirrors, so `api.generated.ts`
  renders each as its union. The JSON is unchanged: Jackson writes an enum by name.
- `UpgradeTrackingSummaryAdapter`, in `tracking`, converts by name with `valueOf`. It already depends on
  `upgrade`, so no new edge appears on the context map.
- `UpgradeTrackingEnumMirrorContractTest` fails the build when a mirror and its original differ in their
  constants or their order. A constant `tracking` gains without the mirror would otherwise fail the
  adapter's `valueOf` on the first upgrade configured with it, as a 500.
- The names carry the context's prefix because the generator keys by simple name.

The same holds for any later case: a context that needs another's closed set, where the arrow forbids
the import, mirrors it in its own `domain.model` under a prefixed name, with a contract test beside the
others in `com.healthupgrades.contract`.

## Consequences

**What this makes easy**

- **The SPA's compiler sees the closed set.** A comparison with a value the API never sends, or a
  constant renamed in `tracking`, is a `tsc` error at each use. `frontend/src/types/upgradeTrackingConfig.test.ts`
  fails `npm run build` should either field widen back to `string`.
- **No rule changes, and no new kind of edge.** No import crosses from `upgrade` into `tracking`, and no
  ArchUnit rule was widened.

**What this makes hard**

- **Every closed set is written twice.** Adding a tracking type means adding it to the mirror in the
  same change; the contract test says so, but only at build time.
- **The frontend has two names for one set.** `TrackingType` and `UpgradeTrackingType` are the same
  union under different names. They are assignable to each other, so no code has to choose, but a
  reader may wonder why there are two.
- **`upgrade`'s model holds vocabulary it does not own.** Nothing in `upgrade`'s domain uses the mirrors;
  their class comments say whose they are and point here.

## Alternatives considered

- **Keep the strings.** Rejected: it is the drift NFR-10 rules out, and the generated type cannot
  express the set.
- **Import `tracking`'s enums into `upgrade`.** Rejected: it adds `upgrade → tracking` beside
  `tracking → upgrade`, a cycle `bounded_contexts_are_free_of_cycles` refuses, and the one ADR-002 removed.
- **Move the enums into `common`.** Rejected: they are `tracking`'s domain vocabulary, not the kernel's,
  and `common` would then own two contexts' model to save one copy.
- **Mirror them in `upgrade.application.port.out`, beside the summary.** Rejected: ADR-064's
  `inbound_adapters_do_not_drive_outbound_ports` keeps an `adapter.in` class off `port.out` types except
  for named pairs: a composition adapter and the port it implements or the record that port returns
  (`AccountCredentials`, `UpgradeTrackingSummary`), `SessionEndSocketListener` and `LiveSocketPort`, and
  `UpgradeWebMapper` and the summary port and record. A web DTO is in none of them. A trial with the
  enums there failed that rule with eight violations, from the DTO's fields, accessors and constructor
  and from the composition adapter's `valueOf` calls, so it would have to widen to place two enums.
- **Mirror them in `upgrade.application.port.in`.** Tried first, and rejected:
  - ADR-001 reserves `port.in` for use-case and query interfaces with their command and result records,
    and no use case takes or returns these enums;
  - the summary in `port.out` would then import from `port.in`, which no outbound port type does;
  - the generated class diagram would label the mirrors use-case records.
  It passed the rules only because they match on the package label.
- **Narrow the generated type in `frontend/src/types/index.ts`, intersecting the DTO with the unions.**
  Rejected: it is a hand-kept copy of the wire shape, which ADR-053 exists to remove, and the backend
  would still declare a `String` that nothing checks against `tracking`.
- **Convert with an exhaustive `switch` in the adapter instead of `valueOf`.** It would turn a constant
  `tracking` gains into a compile error, but not a constant only the mirror has, which still needs the
  contract test. Not taken: one check that covers both directions is enough.

## When to revisit

- **A third closed set needs mirroring, or a mirror changes more than once a year.** The copies are then
  a cost worth a generated mirror, or a shared published-language package both contexts depend on.
- **`upgrade` and `tracking` merge, or the arrow between them reverses.** The import becomes legal and
  the mirrors should go.
