package com.healthupgrades.upgrade.domain.model;

/**
 * What kind of change an upgrade is, which is what makes "health upgrade" broader than "habit".
 *
 * <p>The type is descriptive — it drives presentation and filtering, not domain rules. How progress is
 * measured is decided separately by the tracking configuration.
 */
public enum UpgradeType {

    /** A recurring behaviour to establish, e.g. drinking two litres of water a day. */
    HABIT,

    /** An outcome to reach by a date, e.g. running 10km by summer. */
    GOAL,

    /** A time-boxed trial to learn from, e.g. no caffeine after 2pm for two weeks. */
    EXPERIMENT,

    /** A defined routine or one-off procedure to follow, e.g. a morning stretch sequence. */
    PROTOCOL
}
