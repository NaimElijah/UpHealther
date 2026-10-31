package com.healthupgrades.upgrade.application.port.in;

/**
 * How often an upgrade is expected to be acted on, in this context's own terms: the mirror of
 * {@code tracking}'s {@code Frequency}.
 *
 * <p>Restated rather than imported for the reasons {@link UpgradeTrackingType} gives, and held equal to
 * the original by {@code UpgradeTrackingEnumMirrorContractTest} (ADR-077).
 */
public enum UpgradeTrackingFrequency {

    /** Expected every day. */
    DAILY,

    /** Expected once a week. */
    WEEKLY,

    /** Expected once a month. */
    MONTHLY
}
