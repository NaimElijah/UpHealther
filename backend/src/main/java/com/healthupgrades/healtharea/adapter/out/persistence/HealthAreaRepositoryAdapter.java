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
 *
 * <p>The adapter exists so the Spring Data interface can stay package-private: the port is the only
 * thing the rest of the application sees, which is what keeps JPA confined to this package.
 */
@Component
@RequiredArgsConstructor
class HealthAreaRepositoryAdapter implements HealthAreaRepositoryPort {

    private final HealthAreaJpaRepository jpa; // Spring Data proxy

    /** {@inheritDoc} */
    @Override
    public HealthArea save(HealthArea area) {
        return jpa.save(area);
    }

    /** {@inheritDoc} */
    @Override
    public void delete(HealthArea area) {
        jpa.delete(area);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthArea> findByUserId(UUID userId) {
        return jpa.findByUserId(userId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<HealthArea> findByIdAndUserId(UUID id, UUID userId) {
        return jpa.findByIdAndUserId(id, userId);
    }
}
