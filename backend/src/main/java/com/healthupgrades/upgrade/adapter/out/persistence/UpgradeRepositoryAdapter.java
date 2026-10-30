package com.healthupgrades.upgrade.adapter.out.persistence;

import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.upgrade.domain.model.Difficulty; // query filter
import com.healthupgrades.upgrade.domain.model.HealthUpgrade; // domain aggregate
import com.healthupgrades.upgrade.domain.model.UpgradeStatus; // query filter
import com.healthupgrades.upgrade.domain.model.UpgradeType; // query filter
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort; // the port implemented here
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Persistence adapter implementing {@link UpgradeRepositoryPort} by delegating one-to-one to the
 * Spring Data {@link UpgradeJpaRepository}. This is the only place that touches Spring Data for upgrades.
 */
@Component
@RequiredArgsConstructor
class UpgradeRepositoryAdapter implements UpgradeRepositoryPort {

    /** What a request is told when another of the user's requests holds the HARD-limit check. */
    static final String HARD_LIMIT_CHECK_IN_PROGRESS =
            "Another change to your HARD upgrades is in progress. Please retry.";

    private final UpgradeJpaRepository jpa; // Spring Data proxy injected by constructor

    /** {@inheritDoc} */
    @Override
    public HealthUpgrade save(HealthUpgrade upgrade) {
        return jpa.save(upgrade);
    }

    /** {@inheritDoc} */
    @Override
    public void delete(HealthUpgrade upgrade) {
        jpa.delete(upgrade);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<HealthUpgrade> findByIdAndUserId(UUID id, UUID userId) {
        return jpa.findByIdAndUserId(id, userId);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findByUserId(UUID userId) {
        return jpa.findByUserId(userId);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findByUserIdAndStatus(UUID userId, UpgradeStatus status) {
        return jpa.findByUserIdAndStatus(userId, status);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findByUserIdAndType(UUID userId, UpgradeType type) {
        return jpa.findByUserIdAndType(userId, type);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findByUserIdAndAreaId(UUID userId, UUID areaId) {
        return jpa.findByUserIdAndAreaId(userId, areaId);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findByUserIdAndDifficulty(UUID userId, Difficulty difficulty) {
        return jpa.findByUserIdAndDifficulty(userId, difficulty);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findByStatus(UpgradeStatus status) {
        return jpa.findByStatus(status);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findAllById(Iterable<UUID> ids) {
        return jpa.findAllById(ids);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The claim is a transaction-scoped PostgreSQL advisory lock keyed on the user, tried rather than
     * waited for: a waiter would hold its pooled connection for as long as the holder ran, and could join a
     * deadlock with whatever its own transaction had already written. The commit or rollback releases it
     * (ADR-074).
     */
    @Override
    public long countActiveHardUpgradesForLimitCheck(UUID userId) {
        if (!jpa.tryClaimHardLimitCheck(userId)) {
            throw new RetryableConflictException(HARD_LIMIT_CHECK_IN_PROGRESS);
        }
        return jpa.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD);
    }
}
