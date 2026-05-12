package com.healthupgrades.upgrade.infrastructure;

import com.healthupgrades.upgrade.domain.Difficulty;
import com.healthupgrades.upgrade.domain.HealthUpgrade;
import com.healthupgrades.upgrade.domain.UpgradeStatus;
import com.healthupgrades.upgrade.domain.UpgradeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UpgradeRepository extends JpaRepository<HealthUpgrade, UUID> {
    List<HealthUpgrade> findByUserId(UUID userId);
    Optional<HealthUpgrade> findByIdAndUserId(UUID id, UUID userId);
    List<HealthUpgrade> findByUserIdAndStatus(UUID userId, UpgradeStatus status);
    List<HealthUpgrade> findByUserIdAndType(UUID userId, UpgradeType type);
    List<HealthUpgrade> findByUserIdAndAreaId(UUID userId, UUID areaId);
    List<HealthUpgrade> findByUserIdAndDifficulty(UUID userId, Difficulty difficulty);
    long countByUserIdAndStatusAndDifficulty(UUID userId, UpgradeStatus status, Difficulty difficulty);
}
