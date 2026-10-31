package com.healthupgrades.reflection.adapter.out.persistence;

import com.healthupgrades.reflection.domain.model.Reflection;
import com.healthupgrades.reflection.domain.port.out.ReflectionRepositoryPort;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Persistence adapter implementing {@link ReflectionRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class ReflectionRepositoryAdapter implements ReflectionRepositoryPort {

    private final ReflectionJpaRepository jpa;

    /** {@inheritDoc} */
    @Override
    public Reflection save(Reflection reflection) {
        return jpa.save(reflection);
    }

    /** {@inheritDoc} */
    @Override
    public List<Reflection> findByUpgradeIdOrderByDateDescCreatedAtDesc(UUID upgradeId) {
        return jpa.findByUpgradeIdOrderByDateDescCreatedAtDesc(upgradeId);
    }
}
