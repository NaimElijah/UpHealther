package com.healthupgrades.common.domain.audit;

/**
 * The kinds of record an audited action can act on.
 *
 * <p>Each {@link AuditAction} names its own, so a call site cannot pair an action with the wrong kind
 * of resource. It is a closed set for the same reason the actions are: it is a metric label, and a
 * label whose values are not bounded is a cardinality problem waiting to happen.
 */
public enum AuditedResource {

    /** A user account. */
    USER,

    /** A health upgrade — the aggregate this application exists to move through a lifecycle. */
    HEALTH_UPGRADE,

    /** A health area an upgrade can be filed under. */
    HEALTH_AREA,

    /** How an upgrade is measured. */
    TRACKING_CONFIG,

    /** One day's progress against one upgrade. */
    PROGRESS_ENTRY,

    /** A written reflection about an upgrade. */
    REFLECTION,

    /** A reminder attached to an upgrade. */
    REMINDER
}
