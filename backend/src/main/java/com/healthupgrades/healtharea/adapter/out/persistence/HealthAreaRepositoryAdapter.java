package com.healthupgrades.healtharea.adapter.out.persistence;

import com.healthupgrades.healtharea.domain.model.HealthArea; // domain aggregate
import com.healthupgrades.healtharea.domain.port.out.HealthAreaRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link HealthAreaRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class HealthAreaRepositoryAdapter implements HealthAreaRepositoryPort {

    private final HealthAreaJpaRepository jpa; // Spring Data proxy

    @Override
    public HealthArea save(HealthArea area) {
        return jpa.save(area); // delegate persist
    }

    @Override
    public void delete(HealthArea area) {
        jpa.delete(area); // delegate delete
    }

    @Override
    public List<HealthArea> findByUserId(UUID userId) {
        return jpa.findByUserId(userId); // delegate
    }

    @Override
    public Optional<HealthArea> findByIdAndUserId(UUID id, UUID userId) {
        return jpa.findByIdAndUserId(id, userId); // delegate ownership-scoped lookup
    }
}
