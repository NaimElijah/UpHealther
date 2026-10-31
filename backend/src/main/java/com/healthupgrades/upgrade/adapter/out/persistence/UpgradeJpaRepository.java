package com.healthupgrades.upgrade.adapter.out.persistence;

import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import jakarta.persistence.Tuple;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository backing {@link UpgradeRepositoryAdapter}.
 *
 * <p>Package-private on purpose: it is an internal persistence detail and must not be referenced
 * outside this adapter package — callers depend on {@code UpgradeRepositoryPort} instead.
 */
interface UpgradeJpaRepository extends JpaRepository<HealthUpgrade, UUID> {
    List<HealthUpgrade> findByUserId(UUID userId);

    Optional<HealthUpgrade> findByIdAndUserId(UUID id, UUID userId);

    List<HealthUpgrade> findByStatusAndTargetEndDateBefore(UpgradeStatus status, LocalDate date);

    List<HealthUpgrade> findByUserIdAndStatus(UUID userId, UpgradeStatus status);

    List<HealthUpgrade> findByUserIdAndType(UUID userId, UpgradeType type);

    List<HealthUpgrade> findByUserIdAndAreaId(UUID userId, UUID areaId);

    List<HealthUpgrade> findByUserIdAndDifficulty(UUID userId, Difficulty difficulty);

    long countByUserIdAndStatusAndDifficulty(UUID userId, UpgradeStatus status, Difficulty difficulty);

    /**
     * How many upgrades in a status each user has, across every user: one row per owner that has any.
     *
     * @param status the status to count
     * @return each owner's id under {@code userId} and their count under {@code upgrades}
     */
    @Query("SELECT u.userId AS userId, COUNT(u) AS upgrades FROM HealthUpgrade u "
            + "WHERE u.status = :status GROUP BY u.userId")
    List<Tuple> countByOwnerInStatus(@Param("status") UpgradeStatus status);

    /**
     * Tries to claim the user's HARD-limit check: takes the user's advisory lock if no other transaction
     * holds it, without waiting, and holds it until this transaction ends.
     *
     * <p>The key is a 64-bit hash of the user id, prefixed so another per-user lock gets a key of its own.
     * {@code MANDATORY} because outside a transaction the lock would end with the statement (ADR-074).
     *
     * @param userId the user whose HARD-limit check is claimed
     * @return whether the claim was granted; false when another transaction holds it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value = "SELECT pg_try_advisory_xact_lock(hashtextextended('hard-limit ' || CAST(:userId AS text), 0))",
            nativeQuery = true)
    boolean tryClaimHardLimitCheck(@Param("userId") UUID userId);
}
