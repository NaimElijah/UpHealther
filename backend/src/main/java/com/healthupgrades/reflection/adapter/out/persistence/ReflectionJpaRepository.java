package com.healthupgrades.reflection.adapter.out.persistence;

import com.healthupgrades.reflection.domain.model.Reflection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository backing {@link ReflectionRepositoryAdapter}; package-private internal detail.
 */
interface ReflectionJpaRepository extends JpaRepository<Reflection, UUID> {
    // Derived query. Several reflections may share a date, so createdAt breaks the tie.
    List<Reflection> findByUpgradeIdOrderByDateDescCreatedAtDesc(UUID upgradeId);

    List<Reflection> findByUserId(UUID userId);
}
