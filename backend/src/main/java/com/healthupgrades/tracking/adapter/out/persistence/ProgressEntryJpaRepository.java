package com.healthupgrades.tracking.adapter.out.persistence;

import com.healthupgrades.tracking.domain.model.ProgressEntry;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository backing {@link ProgressEntryRepositoryAdapter}; package-private internal detail.
 */
interface ProgressEntryJpaRepository extends JpaRepository<ProgressEntry, UUID> {
    List<ProgressEntry> findByUpgradeId(UUID upgradeId); // derived query

    List<ProgressEntry> findByUpgradeIdOrderByDateDesc(UUID upgradeId);

    List<ProgressEntry> findByUserIdAndUpgradeIdIn(UUID userId, Collection<UUID> upgradeIds); // batch, owner's only

    Optional<ProgressEntry> findByUpgradeIdAndDate(UUID upgradeId, LocalDate date); // derived query

    boolean existsByUpgradeIdAndDate(UUID upgradeId, LocalDate date);

    List<ProgressEntry> findByUserId(UUID userId); // derived query

    List<ProgressEntry> findByUserIdAndDate(UUID userId, LocalDate date);

    List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end);

    // Who logged anything on a day, across every user: the check-in sweep's guard, read once per run.
    @Query("SELECT DISTINCT p.userId FROM ProgressEntry p WHERE p.date = :date")
    Set<UUID> findUserIdsWithEntriesOn(LocalDate date);

    @Query(
            "SELECT p FROM ProgressEntry p WHERE p.upgradeId = :upgradeId AND p.date BETWEEN :start AND :end ORDER BY p.date")
    List<ProgressEntry> findByUpgradeIdAndDateBetween(UUID upgradeId, LocalDate start, LocalDate end);
}
