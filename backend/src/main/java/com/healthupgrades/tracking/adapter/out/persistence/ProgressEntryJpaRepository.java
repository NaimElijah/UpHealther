package com.healthupgrades.tracking.adapter.out.persistence;

import com.healthupgrades.tracking.domain.model.ProgressEntry; // managed entity
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository backing {@link ProgressEntryRepositoryAdapter}; package-private internal detail.
 * Query methods are kept verbatim from the previous infrastructure repository.
 */
interface ProgressEntryJpaRepository extends JpaRepository<ProgressEntry, UUID> {
    List<ProgressEntry> findByUpgradeId(UUID upgradeId); // derived query
    List<ProgressEntry> findByUpgradeIdOrderByDateDesc(UUID upgradeId); // derived query, newest first
    Optional<ProgressEntry> findByUpgradeIdAndDate(UUID upgradeId, LocalDate date); // derived query
    boolean existsByUpgradeIdAndDate(UUID upgradeId, LocalDate date); // derived existence check
    List<ProgressEntry> findByUserId(UUID userId); // derived query
    List<ProgressEntry> findByUserIdAndDate(UUID userId, LocalDate date); // derived query
    List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end); // derived range query

    // Explicit JPQL range query for a single upgrade, ordered by date.
    @Query("SELECT p FROM ProgressEntry p WHERE p.upgradeId = :upgradeId AND p.date BETWEEN :start AND :end ORDER BY p.date")
    List<ProgressEntry> findByUpgradeIdAndDateBetween(UUID upgradeId, LocalDate start, LocalDate end);
}
