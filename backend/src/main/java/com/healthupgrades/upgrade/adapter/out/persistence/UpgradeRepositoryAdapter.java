package com.healthupgrades.upgrade.adapter.out.persistence;

import com.healthupgrades.upgrade.domain.model.Difficulty; // query filter
import com.healthupgrades.upgrade.domain.model.HealthUpgrade; // domain aggregate
import com.healthupgrades.upgrade.domain.model.UpgradeStatus; // query filter
import com.healthupgrades.upgrade.domain.model.UpgradeType; // query filter
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link UpgradeRepositoryPort} by delegating one-to-one to the
 * Spring Data {@link UpgradeJpaRepository}. This is the only place that touches Spring Data for upgrades.
 */
@Component
@RequiredArgsConstructor
class UpgradeRepositoryAdapter implements UpgradeRepositoryPort {

    private final UpgradeJpaRepository jpa; // Spring Data proxy injected by constructor

    @Override
    public HealthUpgrade save(HealthUpgrade upgrade) {
        return jpa.save(upgrade); // delegate persist
    }

    @Override
    public void delete(HealthUpgrade upgrade) {
        jpa.delete(upgrade); // delegate delete
    }

    @Override
    public Optional<HealthUpgrade> findByIdAndUserId(UUID id, UUID userId) {
        return jpa.findByIdAndUserId(id, userId); // delegate ownership-scoped lookup
    }

    @Override
    public List<HealthUpgrade> findByUserId(UUID userId) {
        return jpa.findByUserId(userId); // delegate
    }

    @Override
    public List<HealthUpgrade> findByUserIdAndStatus(UUID userId, UpgradeStatus status) {
        return jpa.findByUserIdAndStatus(userId, status); // delegate
    }

    @Override
    public List<HealthUpgrade> findByUserIdAndType(UUID userId, UpgradeType type) {
        return jpa.findByUserIdAndType(userId, type); // delegate
    }

    @Override
    public List<HealthUpgrade> findByUserIdAndAreaId(UUID userId, UUID areaId) {
        return jpa.findByUserIdAndAreaId(userId, areaId); // delegate
    }

    @Override
    public List<HealthUpgrade> findByUserIdAndDifficulty(UUID userId, Difficulty difficulty) {
        return jpa.findByUserIdAndDifficulty(userId, difficulty); // delegate
    }

    @Override
    public List<HealthUpgrade> findByStatus(UpgradeStatus status) {
        return jpa.findByStatus(status); // delegate
    }

    @Override
    public List<HealthUpgrade> findAllById(Iterable<UUID> ids) {
        return jpa.findAllById(ids); // delegate to the inherited JpaRepository batch lookup
    }

    @Override
    public long countByUserIdAndStatusAndDifficulty(UUID userId, UpgradeStatus status, Difficulty difficulty) {
        return jpa.countByUserIdAndStatusAndDifficulty(userId, status, difficulty); // delegate count
    }
}
