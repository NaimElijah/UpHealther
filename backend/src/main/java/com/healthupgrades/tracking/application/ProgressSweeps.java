package com.healthupgrades.tracking.application;

import com.healthupgrades.tracking.application.port.in.ProgressSweepQuery;
import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The check-in sweep's read of who logged on a day, across every user: {@link ProgressSweepQuery} over
 * the repository.
 *
 * <p>A class of its own rather than more methods on the context's service, because a controller holds that
 * service: a sweep method on it would be one call away from a request handler, whatever the port's own
 * dependents are. Only the jobs' application services hold this port, which
 * {@code HexagonalArchitectureTest} enforces.
 */
@Service
@RequiredArgsConstructor
class ProgressSweeps implements ProgressSweepQuery {

    private final ProgressEntryRepositoryPort progressRepository; // outbound persistence port

    /** {@inheritDoc} */
    @Override
    public Set<UUID> findUserIdsWithEntriesOn(LocalDate date) {
        return progressRepository.findUserIdsWithEntriesOn(date);
    }
}
