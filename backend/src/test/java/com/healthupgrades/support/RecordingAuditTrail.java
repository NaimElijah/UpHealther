package com.healthupgrades.support;

import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.port.out.AuditTrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An {@link AuditTrail} that keeps what it was told, for a test to assert against.
 *
 * <p>This exists instead of a Mockito mock for a reason worth knowing before anyone swaps it back:
 * {@code AuditTrail.recording} is a {@code default} method, and a mock stubs those too — so
 * {@code recording(...)} would return {@code null} without ever running the operation it was given,
 * and every service under test would quietly stop doing its work while its test still passed. Keeping
 * a real implementation means the wrapper, including the branch that records a refusal, is exercised
 * by every service test rather than only by its own.
 */
public class RecordingAuditTrail implements AuditTrail {

    private final List<AuditEvent> recorded = new ArrayList<>();

    @Override
    public void record(AuditEvent event) {
        recorded.add(event);
    }

    /** Everything recorded so far, in order. */
    public List<AuditEvent> recorded() {
        return List.copyOf(recorded);
    }

    /** The only event recorded for {@code action}, or empty when there is none. */
    public Optional<AuditEvent> only(AuditAction action) {
        List<AuditEvent> matches = recorded.stream().filter(e -> e.action() == action).toList();
        if (matches.size() > 1) {
            throw new AssertionError(action + " was recorded " + matches.size() + " times, expected at most one");
        }
        return matches.stream().findFirst();
    }

    /** Whether {@code action} was recorded with {@code outcome}. */
    public boolean recorded(AuditAction action, AuditOutcome outcome) {
        return recorded.stream().anyMatch(e -> e.action() == action && e.outcome() == outcome);
    }
}
