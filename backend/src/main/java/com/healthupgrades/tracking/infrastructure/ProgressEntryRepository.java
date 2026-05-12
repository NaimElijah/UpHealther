package com.healthupgrades.tracking.infrastructure;

import com.healthupgrades.tracking.domain.ProgressEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProgressEntryRepository extends JpaRepository<ProgressEntry, UUID> {
    List<ProgressEntry> findByUpgradeId(UUID upgradeId);
    List<ProgressEntry> findByUpgradeIdOrderByDateDesc(UUID upgradeId);
    Optional<ProgressEntry> findByUpgradeIdAndDate(UUID upgradeId, LocalDate date);
    boolean existsByUpgradeIdAndDate(UUID upgradeId, LocalDate date);
    List<ProgressEntry> findByUserId(UUID userId);
    List<ProgressEntry> findByUserIdAndDate(UUID userId, LocalDate date);
    List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end);

    @Query("SELECT p FROM ProgressEntry p WHERE p.upgradeId = :upgradeId AND p.date BETWEEN :start AND :end ORDER BY p.date")
    List<ProgressEntry> findByUpgradeIdAndDateBetween(UUID upgradeId, LocalDate start, LocalDate end);
}
