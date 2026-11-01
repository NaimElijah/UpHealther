package com.healthupgrades.healtharea.domain.port.out;

import com.healthupgrades.healtharea.domain.model.HealthArea;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting and querying {@link HealthArea} aggregates.
 */
public interface HealthAreaRepositoryPort {

    /** Persists a new or updated health area and returns the managed instance. */
    HealthArea save(HealthArea area);

    /** Removes a health area from the store. */
    void delete(HealthArea area);

    /** All health areas owned by a user, in no particular order. */
    List<HealthArea> findByUserId(UUID userId);

    /** Ownership-scoped single lookup by id: empty when the area is missing or another user's. */
    Optional<HealthArea> findByIdAndUserId(UUID id, UUID userId);
}
