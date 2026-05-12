package com.healthupgrades.healtharea.infrastructure;

import com.healthupgrades.healtharea.domain.HealthArea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthAreaRepository extends JpaRepository<HealthArea, UUID> {
    List<HealthArea> findByUserId(UUID userId);
    Optional<HealthArea> findByIdAndUserId(UUID id, UUID userId);
    void deleteByIdAndUserId(UUID id, UUID userId);
}
