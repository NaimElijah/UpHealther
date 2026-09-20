package com.healthupgrades.common.domain.audit;

/**
 * The closed vocabulary of things this application audits.
 *
 * <p>An enum rather than a string for three reasons: it is a metric label and therefore has to be
 * bounded; a typo cannot reach production; and reading this file is how somebody finds out what the
 * audit trail does and does not cover, which a scattering of string literals would never tell them.
 *
 * <p>It lives in {@code common} because the audit trail is one trail, not nine. That does mean a
 * context adding an operation edits a file outside itself — the same trade-off the shared kernel makes
 * everywhere else, and the reason it is worth paying here is that the alternative is a per-context
 * vocabulary nobody can enumerate.
 *
 * <p><b>What is deliberately absent:</b> notifications. They are raised by schedulers and event
 * listeners rather than by a person — {@code dispatchReminders} alone runs every minute — so auditing
 * them would record the system talking to itself, at volume, under the heading of who did what.
 * Marking one read is a display flag, not a change to the user's record.
 * See {@code docs/ADRs/ADR-011-audit-as-a-log-stream.md}.
 */
public enum AuditAction {

    /** A visitor registered and became a user. */
    AUTH_REGISTER("auth.register", AuditedResource.USER),

    /** Somebody presented credentials. Refused as often as allowed, and both are worth knowing. */
    AUTH_LOGIN("auth.login", AuditedResource.USER),

    /** A session exchanged its refresh credential for a new one and carried on. */
    AUTH_REFRESH("auth.refresh", AuditedResource.AUTH_SESSION),

    /** Somebody signed out, ending one session and leaving their other devices alone. */
    AUTH_LOGOUT("auth.logout", AuditedResource.AUTH_SESSION),

    /**
     * A refresh credential that had already been exchanged was presented again, after the grace
     * window. Two parties held one session's credentials, so the session was revoked for both.
     * The one entry in this enum that should stay at zero; a non-zero count is worth alerting on.
     *
     * <p>Keyed {@code auth.reuse} rather than {@code auth.token-reuse}: a key is a metric label and a
     * log-query term, and the shape every other one has is two lowercase words with a dot between.
     */
    AUTH_TOKEN_REUSE("auth.reuse", AuditedResource.AUTH_SESSION),

    /**
     * An administrator switched an account off, ending every session it held.
     *
     * <p>The three below are the only actions in this enum where the actor and the resource are
     * different people. That is exactly why they are audited: everything else records somebody
     * acting on their own records, and these record somebody acting on another's.
     */
    ADMIN_DISABLE("admin.disable", AuditedResource.USER),

    /** An administrator switched an account back on. */
    ADMIN_ENABLE("admin.enable", AuditedResource.USER),

    /** An administrator granted or revoked a role. */
    ADMIN_ROLE("admin.role", AuditedResource.USER),

    UPGRADE_CREATE("upgrade.create", AuditedResource.HEALTH_UPGRADE),
    UPGRADE_UPDATE("upgrade.update", AuditedResource.HEALTH_UPGRADE),
    UPGRADE_DELETE("upgrade.delete", AuditedResource.HEALTH_UPGRADE),
    UPGRADE_PLAN("upgrade.plan", AuditedResource.HEALTH_UPGRADE),
    UPGRADE_ACTIVATE("upgrade.activate", AuditedResource.HEALTH_UPGRADE),
    UPGRADE_PAUSE("upgrade.pause", AuditedResource.HEALTH_UPGRADE),
    UPGRADE_COMPLETE("upgrade.complete", AuditedResource.HEALTH_UPGRADE),
    UPGRADE_ABANDON("upgrade.abandon", AuditedResource.HEALTH_UPGRADE),
    UPGRADE_RESCHEDULE("upgrade.reschedule", AuditedResource.HEALTH_UPGRADE),

    AREA_CREATE("area.create", AuditedResource.HEALTH_AREA),
    AREA_UPDATE("area.update", AuditedResource.HEALTH_AREA),
    AREA_DELETE("area.delete", AuditedResource.HEALTH_AREA),

    /** Changing how an upgrade is measured changes what its past progress means. */
    TRACKING_CONFIGURE("tracking.configure", AuditedResource.TRACKING_CONFIG),
    PROGRESS_RECORD("progress.record", AuditedResource.PROGRESS_ENTRY),

    /** Reflections are append-only (BR-13), so there is nothing else to audit about one. */
    REFLECTION_CREATE("reflection.create", AuditedResource.REFLECTION),

    REMINDER_CREATE("reminder.create", AuditedResource.REMINDER),
    REMINDER_UPDATE("reminder.update", AuditedResource.REMINDER),
    REMINDER_DELETE("reminder.delete", AuditedResource.REMINDER);

    private final String key;
    private final AuditedResource resource;

    AuditAction(String key, AuditedResource resource) {
        this.key = key;
        this.resource = resource;
    }

    /**
     * The dotted name written to the log and used as a metric tag.
     *
     * <p>Stable on purpose: it is what a saved log query or a dashboard matches on, so it is a contract
     * and not a display string. Renaming a constant is free; changing a key is a breaking change.
     */
    public String key() {
        return key;
    }

    /** The kind of record this action acts on. */
    public AuditedResource resource() {
        return resource;
    }
}
