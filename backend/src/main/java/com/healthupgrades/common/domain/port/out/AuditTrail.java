package com.healthupgrades.common.domain.port.out;

import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Outbound port for recording what was attempted against a user's records, and whether it was allowed.
 *
 * <p>Cross-cutting rather than owned by one context, like {@link DomainEventPublisher} beside it: every
 * context records through it, and {@code common.domain.audit} holds the vocabulary they share.
 *
 * <p>Implementers write one method. The {@code recording} wrappers are defaults rather than call-site
 * try/catch because <b>a trail that records only successes is not a trail</b> — an ownership check that
 * refused, a rule that rejected a transition and a conflict that lost a race are the entries somebody
 * will actually go looking for. Leaving that to each call site means it is written twenty times and
 * forgotten once.
 */
public interface AuditTrail {

    /** Records one attempt. Implementations must not throw: an audit failure must not fail the work. */
    void record(AuditEvent event);

    /**
     * Runs an operation that produces something, and records how it ended either way.
     *
     * <p>The exception is rethrown untouched, so the caller's contract and the HTTP status mapping are
     * unchanged — this observes the operation, it does not handle it.
     *
     * @param action      what is being attempted
     * @param actorUserId the user attempting it
     * @param resourceId  the record acted on, or {@code null} when there is not one yet
     * @param operation   the work to run and observe
     * @return whatever the operation returned
     */
    default <T> T recording(AuditAction action, UUID actorUserId, UUID resourceId, Supplier<T> operation) {
        try {
            T result = operation.get();
            record(AuditEvent.allowed(action, actorUserId, resourceId));
            return result;
        } catch (RuntimeException thrown) {
            record(AuditEvent.from(action, actorUserId, resourceId, thrown));
            throw thrown;
        }
    }

    /** As {@link #recording(AuditAction, UUID, UUID, Supplier)}, for an operation that returns nothing. */
    default void recording(AuditAction action, UUID actorUserId, UUID resourceId, Runnable operation) {
        recording(action, actorUserId, resourceId, () -> {
            operation.run();
            return null;
        });
    }

    /**
     * Runs an operation that brings a record into existence, and names that record in the entry.
     *
     * <p>A creation cannot use {@link #recording(AuditAction, UUID, UUID, Supplier)}: the resource id is
     * an argument there, and a new upgrade has no id until the save returns — so the entry would say
     * somebody created something without saying what. That is the one question a creation entry exists
     * to answer.
     *
     * @param createdId reads the new record's id off the result, once there is one
     */
    default <T> T recordingCreation(AuditAction action, UUID actorUserId, Supplier<T> operation,
                                    Function<T, UUID> createdId) {
        try {
            T result = operation.get();
            record(AuditEvent.allowed(action, actorUserId, createdId.apply(result)));
            return result;
        } catch (RuntimeException thrown) {
            // Nothing was created, so there is genuinely no resource to name.
            record(AuditEvent.from(action, actorUserId, null, thrown));
            throw thrown;
        }
    }
}
