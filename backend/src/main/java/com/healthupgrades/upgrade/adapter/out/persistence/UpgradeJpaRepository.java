package com.healthupgrades.upgrade.adapter.out.persistence;

import com.healthupgrades.upgrade.domain.model.Difficulty; // query filter
import com.healthupgrades.upgrade.domain.model.HealthUpgrade; // managed entity
import com.healthupgrades.upgrade.domain.model.UpgradeStatus; // query filter
import com.healthupgrades.upgrade.domain.model.UpgradeType; // query filter
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
    List<HealthUpgrade> findByUserId(UUID userId); // derived query: all of a user's upgrades

    Optional<HealthUpgrade> findByIdAndUserId(UUID id, UUID userId); // derived query: ownership-scoped lookup

    List<HealthUpgrade> findByStatus(UpgradeStatus status); // derived query: cross-user by status

    List<HealthUpgrade> findByUserIdAndStatus(UUID userId, UpgradeStatus status); // derived query

    List<HealthUpgrade> findByUserIdAndType(UUID userId, UpgradeType type); // derived query

    List<HealthUpgrade> findByUserIdAndAreaId(UUID userId, UUID areaId); // derived query

    List<HealthUpgrade> findByUserIdAndDifficulty(UUID userId, Difficulty difficulty); // derived query

    long countByUserIdAndStatusAndDifficulty(UUID userId, UpgradeStatus status, Difficulty difficulty); // derived count

    /**
     * Takes the user's HARD-limit advisory lock, waiting for it, and holds it until the transaction ends.
     *
     * <p>The key is a 64-bit hash of the user id, prefixed so another per-user lock gets a key of its own.
     * The function is called in {@code FROM} because it returns {@code void}, which JPA cannot read back;
     * the statement returns a constant instead. {@code MANDATORY} because outside a transaction the lock
     * would end with the statement (ADR-074).
     *
     * @param userId the user whose HARD-limit checks are serialised
     * @return always 1
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('hard-limit ' || CAST(:userId AS text), 0))",
            nativeQuery = true)
    int lockHardLimitChecks(@Param("userId") UUID userId);
}
