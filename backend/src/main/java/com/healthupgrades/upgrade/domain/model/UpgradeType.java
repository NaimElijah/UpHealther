package com.healthupgrades.upgrade.domain.model;

/**
 * What kind of change an upgrade is, which is what makes "health upgrade" broader than "habit".
 *
 * <p>The type is descriptive — it drives presentation and filtering, not domain rules. How progress is
 * measured is decided separately by the tracking configuration.
 *
 * <p>The first eight values are the kinds the README defines as the product. They and the frontend's
 * {@code UpgradeType} union must stay identical: the value is bound straight from the request body, so
 * a name the frontend offers and this enum lacks fails to deserialize. The union is generated from this
 * enum, and {@code FrontendWireTypesContractTest} fails the build when the generated file is behind it.
 */
public enum UpgradeType {

    /** A recurring behaviour to establish, e.g. drinking two litres of water a day. */
    HABIT,

    /** A single task to get done, e.g. booking a dentist appointment. */
    ONE_TIME_ACTION,

    /** Swapping something out for a better alternative, e.g. replacing toxic cleaning products. */
    PRODUCT_REPLACEMENT,

    /** A defined sequence to follow regularly, e.g. a morning meditation and stretch. */
    ROUTINE,

    /** An outcome to reach by a date, e.g. running 10km by summer. */
    GOAL,

    /** A time-boxed trial to learn from, e.g. no caffeine after 2pm for two weeks. */
    EXPERIMENT,

    /** Something to read up on or study, e.g. reading about intermittent fasting. */
    LEARNING_TASK,

    /** A preventive or medical errand, e.g. getting bloodwork done. */
    MEDICAL_PREVENTIVE,

    /**
     * A prescribed protocol to follow.
     *
     * <p>Retained rather than offered: it predates the kinds above, appears in no requirement and is not
     * selectable in the UI, but a deployed database may hold rows carrying it — and Hibernate would fail
     * to read those back if the constant were removed.
     *
     * <p>No new row can carry it: {@code HealthUpgrade} refuses it on creation, and on an edit unless the
     * upgrade already holds it, so the removal condition below can be met.
     *
     * @deprecated not one of the documented upgrade kinds. Existing rows keep it and still render; new
     *             code should choose from the eight above. Remove once no stored row carries it.
     */
    @Deprecated
    PROTOCOL;

    /**
     * Whether this kind is retired: kept so stored rows still read back, but never chosen anew.
     *
     * <p>Asked here rather than by comparing with {@link #PROTOCOL} at the call site, so the deprecated
     * constant is named in one place.
     *
     * @return true for {@link #PROTOCOL}
     */
    public boolean isRetired() {
        return this == PROTOCOL;
    }
}
