package com.healthupgrades.reflection.adapter.out.persistence;

import com.healthupgrades.reflection.domain.model.Reflection; // managed entity
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository backing {@link ReflectionRepositoryAdapter}; package-private internal detail.
 */
interface ReflectionJpaRepository extends JpaRepository<Reflection, UUID> {
    List<Reflection> findByUpgradeIdOrderByDateDesc(UUID upgradeId); // derived query, newest first
    List<Reflection> findByUserId(UUID userId); // derived query
}
