package com.healthupgrades.auth.adapter.in.composition;

import com.healthupgrades.auth.application.port.in.SessionQuery;
import com.healthupgrades.common.domain.port.out.SessionStatusPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Driving adapter that answers the shared kernel's {@link SessionStatusPort} from this context's own
 * {@link SessionQuery}.
 *
 * <p>The supplying half of an inverted dependency. The security adapter in {@code common} has to know
 * whether the session an access token names is still live; this context is what knows. Declaring the
 * contract there and implementing it here leaves a single arrow — from {@code auth} to {@code common} —
 * where a direct call would have pointed the shared kernel at a bounded context and closed a cycle.
 *
 * <p>The same pattern as {@code UpgradeTrackingSummaryAdapter}, and the reason the indirection is one
 * class rather than a package: the translation is trivial because the contract was kept trivial.
 */
@Component
@RequiredArgsConstructor
public class SessionStatusAdapter implements SessionStatusPort {

    private final SessionQuery sessionQuery; // this context's own inbound read port

    /** {@inheritDoc} */
    @Override
    public boolean isActive(UUID sessionId) {
        return sessionQuery.isActive(sessionId);
    }
}
