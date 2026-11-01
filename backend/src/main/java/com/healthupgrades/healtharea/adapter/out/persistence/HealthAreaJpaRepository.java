package com.healthupgrades.healtharea.adapter.out.persistence;

import com.healthupgrades.healtharea.domain.model.HealthArea;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository backing {@link HealthAreaRepositoryAdapter}; package-private internal detail.
 */
interface HealthAreaJpaRepository extends JpaRepository<HealthArea, UUID> {
    List<HealthArea> findByUserId(UUID userId);

    Optional<HealthArea> findByIdAndUserId(UUID id, UUID userId);

    void deleteByIdAndUserId(UUID id, UUID userId); // derived delete: ownership-scoped
}
