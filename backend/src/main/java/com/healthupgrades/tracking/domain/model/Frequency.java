package com.healthupgrades.tracking.domain.model;

/**
 * How often an upgrade is expected to be acted on.
 *
 * <p>Descriptive rather than enforced: nothing rejects a daily entry on a weekly upgrade. Streaks are
 * counted in consecutive days regardless of this value, so a weekly upgrade does not accumulate one.
 */
public enum Frequency {

    /** Expected every day. */
    DAILY,

    /** Expected once a week. */
    WEEKLY,

    /** Expected once a month. */
    MONTHLY
}
