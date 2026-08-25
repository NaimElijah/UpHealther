package com.healthupgrades.common.domain.audit;

import java.util.UUID;

/**
 * One entry in the audit trail: who attempted what, against which record, and how it ended.
 *
 * <p><b>There is deliberately nowhere to put free text.</b> Every field is an enum or an identifier,
 * so NFR-6 — the application does not log personal data — is enforced by the type rather than by
 * whoever reviews the next call site. An upgrade's title, a reflection's body and an email address
 * cannot be audited because they cannot be represented. When an audit entry is not enough to
 * understand what happened, the trace id on the line leads to the request that produced it.
 *
 * <p>The occurrence time is not a field either: the log line is timestamped by the logging framework,
 * and a second timestamp chosen by a caller would only ever be a way for the two to disagree.
 *
 * @param action     what was attempted
 * @param actorUserId the user who attempted it, or {@code null} where the actor is genuinely unknown —
 *                    a refused login is the case that matters, since the submitted email is personal
 *                    data and is therefore never recorded
 * @param resourceId  the record acted on, or {@code null} where there is not one: registration has no
 *                    user yet, and a login acts on no record at all
 * @param outcome     how the attempt ended
 */
public record AuditEvent(AuditAction action, UUID actorUserId, UUID resourceId, AuditOutcome outcome) {

    /**
     * @throws IllegalArgumentException if the action or outcome is missing, which would produce an
     *         entry that cannot be read or counted
     */
    public AuditEvent {
        if (action == null) throw new IllegalArgumentException("An audit event must name its action");
        if (outcome == null) throw new IllegalArgumentException("An audit event must state its outcome");
    }

    /** The attempt succeeded. */
    public static AuditEvent allowed(AuditAction action, UUID actorUserId, UUID resourceId) {
        return new AuditEvent(action, actorUserId, resourceId, AuditOutcome.ALLOWED);
    }

    /** The attempt ended in the exception given, classified by {@link AuditOutcome#of}. */
    public static AuditEvent from(AuditAction action, UUID actorUserId, UUID resourceId, RuntimeException thrown) {
        return new AuditEvent(action, actorUserId, resourceId, AuditOutcome.of(thrown));
    }
}
