package com.healthupgrades.upgrade.domain.model;

/**
 * Where an upgrade stands in its lifecycle.
 *
 * <p>The transitions, all guarded by {@code HealthUpgrade}:
 *
 * <pre>
 * IDEA → PLANNED → ACTIVE ⇄ PAUSED
 *                  ACTIVE → COMPLETED
 *   (any but COMPLETED/ABANDONED) → ABANDONED
 *   ABANDONED --reschedule--&gt; PLANNED
 * </pre>
 *
 * <p>The value is {@code IDEA}, not {@code DRAFT} — the backend enum, the frontend union type and the
 * seed data all have to agree on that spelling.
 */
public enum UpgradeStatus {

    /** Captured but not committed to: the state every upgrade is created in. */
    IDEA,

    /** Committed to a start date, not yet running. */
    PLANNED,

    /** Running: the only state in which progress is expected and a HARD slot is occupied. */
    ACTIVE,

    /** Temporarily stopped, resumable by activating it again. */
    PAUSED,

    /** Finished successfully. Terminal — no transition leaves this state. */
    COMPLETED,

    /** Given up on. Reversible only by rescheduling, which returns it to PLANNED. */
    ABANDONED
}
