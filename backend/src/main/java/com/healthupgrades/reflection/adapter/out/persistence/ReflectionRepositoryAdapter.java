package com.healthupgrades.reflection.adapter.out.persistence;

import com.healthupgrades.reflection.domain.Reflection; // domain aggregate
import com.healthupgrades.reflection.domain.port.out.ReflectionRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link ReflectionRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class ReflectionRepositoryAdapter implements ReflectionRepositoryPort {

    private final ReflectionJpaRepository jpa; // Spring Data proxy

    @Override
    public Reflection save(Reflection reflection) {
        return jpa.save(reflection); // delegate persist
    }

    @Override
    public List<Reflection> findByUpgradeIdOrderByDateDesc(UUID upgradeId) {
        return jpa.findByUpgradeIdOrderByDateDesc(upgradeId); // delegate
    }
}
