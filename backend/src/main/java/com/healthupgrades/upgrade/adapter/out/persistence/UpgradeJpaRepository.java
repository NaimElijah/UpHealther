package com.healthupgrades.upgrade.adapter.out.persistence;

import com.healthupgrades.upgrade.domain.Difficulty; // query filter
import com.healthupgrades.upgrade.domain.HealthUpgrade; // managed entity
import com.healthupgrades.upgrade.domain.UpgradeStatus; // query filter
import com.healthupgrades.upgrade.domain.UpgradeType; // query filter
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
}
