package com.healthupgrades.reminder.application;

import com.healthupgrades.reminder.application.port.in.ReminderSweepQuery;
import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The dispatch sweep's read of every user's enabled reminders: {@link ReminderSweepQuery} over the
 * repository.
 *
 * <p>A class of its own rather than more methods on the context's service, because a controller holds that
 * service: a sweep method on it would be one call away from a request handler, whatever the port's own
 * dependents are. Only the jobs' application services hold this port, which
 * {@code HexagonalArchitectureTest} enforces.
 */
@Service
@RequiredArgsConstructor
class ReminderSweeps implements ReminderSweepQuery {

    private final ReminderRepositoryPort repository; // outbound persistence port

    /** {@inheritDoc} Exposes enabled reminders as domain objects for the notification sweep. */
    @Override
    public List<Reminder> findEnabled() {
        return repository.findByEnabledTrue();
    }
}
