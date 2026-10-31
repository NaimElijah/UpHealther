# ADR-077: A context mirrors another context's closed set in its own port

- **Status:** Accepted
- **Date:** 2026-10-31
- **Scope:**
  - `UpgradeTrackingType` and `UpgradeTrackingFrequency` in
    `backend/src/main/java/com/healthupgrades/upgrade/application/port/in/`.
  - `UpgradeTrackingSummary`, `UpgradeTrackingConfigDto` and `UpgradeTrackingSummaryAdapter`, which carry
    and fill them.
  - `UpgradeTrackingEnumMirrorContractTest`, which holds them equal to `tracking`'s enums.
- **Issue:** [#341](https://github.com/NaimElijah/UpHealther/issues/341), scan 07 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding A7.5. Builds on
  [ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md)'s inverted
  `UpgradeTrackingSummaryPort`, [ADR-053](ADR-053-the-frontends-wire-types-are-generated-from-the-apis-records.md)'s
  generated wire types and [ADR-064](ADR-064-the-backends-layering-rules-beyond-adr-002.md)'s layering rules.

## Context

An upgrade's response embeds its tracking configuration. `upgrade` cannot read it from `tracking`
directly, because `tracking` already depends on `upgrade` for ownership checks and the contexts must
form no cycle, so `upgrade` declares `UpgradeTrackingSummaryPort` and `tracking` fills it in (ADR-002).
The summary record the port returns may not name `tracking`'s types either, or the dependency the port
removes would be back.

So the summary carried the tracking type and the frequency as their names, `String`s, and
`UpgradeTrackingConfigDto` did too. The frontend's types are generated from the response records
(ADR-053), and a `String` is rendered as `string`: `trackingConfig?.trackingType === 'NUMERC'` compiled,
and a constant renamed in `tracking` would reach no `tsc` error in the SPA. NFR-10 requires that the
enums a wire type carries cannot drift. The standalone `TrackingConfigDto`, in `tracking` itself, has
always carried the unions.

Two constraints shape the fix. The wire-type generator keys types by simple name, so a second enum
called `TrackingType` would collide with `tracking`'s. And ADR-064's
`inbound_adapters_do_not_drive_outbound_ports` lets an `adapter.in` class reach only
`UpgradeTrackingSummaryPort` and `UpgradeTrackingSummary` among `port.out` types.

## Decision

**`upgrade` restates the two closed sets as enums of its own, `UpgradeTrackingType` and
`UpgradeTrackingFrequency`, in `upgrade.application.port.in`, and a contract test holds each equal to
the `tracking` enum it mirrors.**

- `UpgradeTrackingSummary` and `UpgradeTrackingConfigDto` are typed by the mirrors, so
  `api.generated.ts` renders each as its union. The JSON is unchanged: Jackson writes an enum by name.
- `UpgradeTrackingSummaryAdapter`, in `tracking`, converts by name with `valueOf`. It already depends on
  `upgrade`'s ports, so no new edge appears on the context map.
- `UpgradeTrackingEnumMirrorContractTest` fails the build when a mirror and its original differ in their
  constants or their order. A constant `tracking` gains without the mirror would otherwise fail the
  adapter's `valueOf` on the first upgrade configured with it, as a 500.
- The names carry the context's prefix because the generator keys by simple name.
- **They live in `port.in`, not beside `UpgradeTrackingSummary` in `port.out`.** Placed there, ADR-064's
  rule refuses them eight times: from the web DTO, whose fields, accessors and constructor take them, and
  from the composition adapter, which calls their `valueOf`. Passing would mean widening that rule's
  allowlist to a web DTO typed by an outbound port's value type. In `port.in` they pass every rule as it
  stands: an inbound adapter may depend on its context's inbound ports, and `ports_name_only_published_types`
  lets the summary in `port.out` name a `..application.port..` type. `port.in` already holds what is not
  an operation, the command and result records; an enum is a value too.

The same holds for any later case: a context that needs another's closed set, where the arrow forbids
the import, mirrors it in its own `port.in` under a prefixed name, with a contract test beside the others
in `com.healthupgrades.contract`.

## Consequences

**What this makes easy**

- **The SPA's compiler sees the closed set.** A comparison with a value the API never sends, or a
  constant renamed in `tracking`, is a `tsc` error at each use. `frontend/src/types/upgradeTrackingConfig.test.ts`
  fails `npm run build` should either field widen back to `string`.
- **The context map does not change.** No import crosses from `upgrade` into `tracking`, and no
  ArchUnit rule was widened.

**What this makes hard**

- **Every closed set is written twice.** Adding a tracking type means adding it to the mirror in the
  same change; the contract test says so, but only at build time.
- **The frontend has two names for one set.** `TrackingType` and `UpgradeTrackingType` are the same
  union under different names. They are assignable to each other, so no code has to choose, but a
  reader may wonder why there are two.
- **The enums sit away from the record that needs them most.** A reader of `UpgradeTrackingSummary`
  finds its field types in the sibling package; their class comments point back here.

## Alternatives considered

- **Keep the strings.** Rejected: it is the drift NFR-10 rules out, and the generated type cannot
  express the set.
- **Import `tracking`'s enums into `upgrade`.** Rejected: it adds `upgrade → tracking` beside
  `tracking → upgrade`, a cycle `bounded_contexts_are_free_of_cycles` refuses, and the one ADR-002 removed.
- **Move the enums into `common`.** Rejected: they are `tracking`'s domain vocabulary, not the kernel's,
  and `common` would then own two contexts' model to save one copy.
- **Mirror them in `upgrade.application.port.out`, beside the summary.** Rejected for the reason in the
  Decision: it needs ADR-064's rule widened for a web DTO, which weakens a rule to place two enums.
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
- **ADR-064's rule is reworded to tell values from operations.** The mirrors could then move beside the
  summary.
