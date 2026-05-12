package com.healthupgrades.reflection.infrastructure;

import com.healthupgrades.reflection.domain.Reflection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReflectionRepository extends JpaRepository<Reflection, UUID> {
    List<Reflection> findByUpgradeIdOrderByDateDesc(UUID upgradeId);
    List<Reflection> findByUserId(UUID userId);
}
