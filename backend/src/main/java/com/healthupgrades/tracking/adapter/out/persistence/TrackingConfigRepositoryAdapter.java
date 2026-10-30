package com.healthupgrades.tracking.adapter.out.persistence;

import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.tracking.domain.model.TrackingConfig; // domain aggregate
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort; // the port implemented here
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Persistence adapter implementing {@link TrackingConfigRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class TrackingConfigRepositoryAdapter implements TrackingConfigRepositoryPort {

    /** PostgreSQL's name for {@code upgrade_id ... UNIQUE} in {@code V1__init_schema.sql}. */
    static final String UPGRADE_UNIQUE_CONSTRAINT = "tracking_configs_upgrade_id_key";

    /** What the loser of a race to configure an upgrade first is told; resending the same request works. */
    static final String CONFIGURED_CONCURRENTLY = "Tracking configuration was saved concurrently. Please retry.";

    private final TrackingConfigJpaRepository jpa; // Spring Data proxy

    /**
     * {@inheritDoc}
     *
     * <p>Flushed, so the unique constraint on {@code upgrade_id} refuses a concurrent first configuration
     * inside this call, where it can be translated, rather than at commit, where it would be a 500. Only
     * that constraint is translated, as {@code UserRepositoryAdapter.saveAndFlush} does for the email: any
     * other integrity failure is a defect, and is rethrown unchanged so the client is not told to retry a
     * request that can never succeed (ADR-074).
     */
    @Override
    public TrackingConfig save(TrackingConfig config) {
        try {
            return jpa.saveAndFlush(config);
        } catch (DataIntegrityViolationException violation) {
            if (violation.getCause() instanceof ConstraintViolationException constraint
                    && UPGRADE_UNIQUE_CONSTRAINT.equals(constraint.getConstraintName())) {
                throw new RetryableConflictException(CONFIGURED_CONCURRENTLY);
            }
            throw violation;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TrackingConfig> findByUpgradeId(UUID upgradeId) {
        return jpa.findByUpgradeId(upgradeId);
    }

    /** {@inheritDoc} */
    @Override
    public List<TrackingConfig> findByUpgradeIdIn(Collection<UUID> upgradeIds) {
        return jpa.findByUpgradeIdIn(upgradeIds);
    }
}
