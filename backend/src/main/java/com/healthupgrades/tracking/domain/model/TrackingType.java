package com.healthupgrades.tracking.domain.model;

/**
 * How progress on an upgrade is measured, which decides both what a progress entry must carry and what
 * counts as a success.
 *
 * <p>The success rule for each is applied by {@code ProgressEvaluationService} — the single place that
 * knows what "done" means for a given type.
 */
public enum TrackingType {

    /** Did it or did not: the entry's completion flag decides. */
    BOOLEAN,

    /** A measured quantity: success is reaching the target, and only when the units agree. */
    NUMERIC,

    /** A one-to-five self-rating: three or better counts as a success. */
    RATING,

    /** A written note: any non-blank text counts as a success. */
    TEXT
}
