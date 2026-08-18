package com.healthupgrades.tracking.adapter.out.persistence;

import com.healthupgrades.tracking.domain.model.ProgressEntry; // domain aggregate
import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link ProgressEntryRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class ProgressEntryRepositoryAdapter implements ProgressEntryRepositoryPort {

    private final ProgressEntryJpaRepository jpa; // Spring Data proxy

    /** {@inheritDoc} */
    @Override
    public ProgressEntry save(ProgressEntry entry) {
        return jpa.save(entry);
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByUpgradeIdAndDate(UUID upgradeId, LocalDate date) {
        return jpa.existsByUpgradeIdAndDate(upgradeId, date);
    }

    /** {@inheritDoc} */
    @Override
    public List<ProgressEntry> findByUpgradeId(UUID upgradeId) {
        return jpa.findByUpgradeId(upgradeId);
    }

    /** {@inheritDoc} */
    @Override
    public List<ProgressEntry> findByUpgradeIdOrderByDateDesc(UUID upgradeId) {
        return jpa.findByUpgradeIdOrderByDateDesc(upgradeId);
    }

    /** {@inheritDoc} */
    @Override
    public List<ProgressEntry> findByUserIdAndDate(UUID userId, LocalDate date) {
        return jpa.findByUserIdAndDate(userId, date);
    }

    /** {@inheritDoc} */
    @Override
    public List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end) {
        return jpa.findByUserIdAndDateBetween(userId, start, end);
    }
}
