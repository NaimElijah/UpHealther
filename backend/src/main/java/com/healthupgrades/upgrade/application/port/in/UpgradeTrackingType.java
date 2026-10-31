package com.healthupgrades.upgrade.application.port.in;

/**
 * How an upgrade's progress is measured, in this context's own terms: the mirror of {@code tracking}'s
 * {@code TrackingType}.
 *
 * <p>The upgrade response embeds its tracking configuration, and the frontend types it from this enum
 * (NFR-10). This context may not import {@code tracking}'s enum, because {@code tracking} already depends
 * on it and the context map must stay acyclic, so the closed set is restated here and
 * {@code UpgradeTrackingEnumMirrorContractTest} fails the build when the two differ. The name differs
 * from the original because the wire-type generator keys types by simple name. It lives in
 * {@code port.in} rather than beside {@code UpgradeTrackingSummary}, because ADR-064 keeps inbound
 * adapters, the web DTO among them, off {@code port.out} types (ADR-077).
 */
public enum UpgradeTrackingType {

    /** Did it or did not. */
    BOOLEAN,

    /** A measured quantity against a target. */
    NUMERIC,

    /** A one-to-five self-rating. */
    RATING,

    /** A written note. */
    TEXT
}
