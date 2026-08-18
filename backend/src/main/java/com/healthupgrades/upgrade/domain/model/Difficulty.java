package com.healthupgrades.upgrade.domain.model;

/**
 * How demanding an upgrade is, as judged by the user who created it.
 *
 * <p>Not merely descriptive: HARD is rationed. A user may run at most three HARD upgrades at once, an
 * invariant enforced by {@code UpgradeSchedulingService} on every route into a running HARD upgrade.
 */
public enum Difficulty {

    /** Little effort or disruption; unlimited. */
    EASY,

    /** Noticeable effort; unlimited. */
    MEDIUM,

    /** Demanding enough to compete for attention — capped at three concurrently ACTIVE. */
    HARD
}
