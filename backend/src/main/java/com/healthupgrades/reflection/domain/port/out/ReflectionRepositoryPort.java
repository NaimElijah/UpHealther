package com.healthupgrades.reflection.domain.port.out;

import com.healthupgrades.reflection.domain.Reflection; // the aggregate this port persists

import java.util.List;
import java.util.UUID;

/**
 * Outbound port for persisting and querying {@link Reflection} aggregates.
 */
public interface ReflectionRepositoryPort {

    /** Persists a new or updated reflection and returns the managed instance. */
    Reflection save(Reflection reflection);

    /** An upgrade's reflections, newest first. */
    List<Reflection> findByUpgradeIdOrderByDateDesc(UUID upgradeId);
}
