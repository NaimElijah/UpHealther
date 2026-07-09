package com.healthupgrades.upgrade.domain.port.out;

import com.healthupgrades.upgrade.domain.Difficulty; // difficulty enum used as a query filter
import com.healthupgrades.upgrade.domain.HealthUpgrade; // the aggregate this port persists
import com.healthupgrades.upgrade.domain.UpgradeStatus; // lifecycle status used as a query filter
import com.healthupgrades.upgrade.domain.UpgradeType; // type enum used as a query filter

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port (driven side) for persisting and querying {@link HealthUpgrade} aggregates.
 *
 * <p>Declared in the domain so the application layer depends only on this abstraction; the concrete
 * Spring Data implementation lives in the persistence adapter. Every method returns domain types.
 */
public interface UpgradeRepositoryPort {

    /** Persists a new or updated upgrade and returns the managed instance. */
    HealthUpgrade save(HealthUpgrade upgrade);

    /** Removes an upgrade from the store. */
    void delete(HealthUpgrade upgrade);

    /** Ownership-scoped single lookup: an upgrade by id that must belong to the given user. */
    Optional<HealthUpgrade> findByIdAndUserId(UUID id, UUID userId);

    /** All upgrades owned by a user. */
    List<HealthUpgrade> findByUserId(UUID userId);

    /** A user's upgrades filtered by lifecycle status. */
    List<HealthUpgrade> findByUserIdAndStatus(UUID userId, UpgradeStatus status);

    /** A user's upgrades filtered by type. */
    List<HealthUpgrade> findByUserIdAndType(UUID userId, UpgradeType type);

    /** A user's upgrades filtered by health area. */
    List<HealthUpgrade> findByUserIdAndAreaId(UUID userId, UUID areaId);

    /** A user's upgrades filtered by difficulty. */
    List<HealthUpgrade> findByUserIdAndDifficulty(UUID userId, Difficulty difficulty);

    /** All upgrades in a status across every user (used by scheduled jobs). */
    List<HealthUpgrade> findByStatus(UpgradeStatus status);

    /** Batch lookup of upgrades by id (used to resolve reminders in bulk). */
    List<HealthUpgrade> findAllById(Iterable<UUID> ids);

    /** Counts a user's upgrades in a status at a difficulty (enforces the max-concurrent-HARD rule). */
    long countByUserIdAndStatusAndDifficulty(UUID userId, UpgradeStatus status, Difficulty difficulty);
}
