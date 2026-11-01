package com.healthupgrades.upgrade.domain.port.out;

import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /**
     * Every ACTIVE upgrade whose target date is before a given day, across every user (used by scheduled
     * jobs): the overdue sweep's candidates, read without the rest of the active upgrades.
     *
     * @param today the day a target date is judged against; an upgrade due on it is not yet late
     * @return the matching upgrades of every user
     */
    List<HealthUpgrade> findActivePastTargetDate(LocalDate today);

    /**
     * How many upgrades in a status each user has, across every user (used by scheduled jobs).
     *
     * @param status the status to count
     * @return each user with at least one upgrade in it, and how many; a user with none is absent
     */
    Map<UUID, Long> countByOwner(UpgradeStatus status);

    /** Batch lookup of upgrades by id (used to resolve reminders in bulk). */
    List<HealthUpgrade> findAllById(Iterable<UUID> ids);

    /**
     * Claims the user's concurrent-HARD limit check (BR-5) for the caller's transaction, then counts the
     * user's ACTIVE HARD upgrades. The claim lasts until that transaction ends, so no other transaction can
     * count for the same user until this one has committed or rolled back what it decided.
     *
     * <p>The count reads rows other than the one the caller then writes, so no version check would see two
     * requests that both counted below the limit. The claim and the count are one method so that no caller
     * can count without claiming (ADR-074).
     *
     * <p><strong>Precondition:</strong> a transaction is active. Outside one, the claim would end with the
     * statement and protect nothing, so the call fails with Spring's {@code IllegalTransactionStateException}
     * (the claim's {@code MANDATORY} propagation) instead.
     *
     * @param userId the user whose limit is checked
     * @return how many of the user's upgrades are ACTIVE at HARD
     * @throws RetryableConflictException if another transaction holds the user's claim; the claim is never
     *                                    waited for, so the caller is refused at once and may resend
     */
    long countActiveHardUpgradesForLimitCheck(UUID userId);
}
