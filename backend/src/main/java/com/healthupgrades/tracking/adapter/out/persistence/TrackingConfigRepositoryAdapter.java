package com.healthupgrades.tracking.adapter.out.persistence;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
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

    /** PostgreSQL's name for {@code upgrade_id ... REFERENCES health_upgrades(id)} in {@code V1__init_schema.sql}. */
    static final String UPGRADE_FOREIGN_KEY = "tracking_configs_upgrade_id_fkey";

    /** What the loser of a race to configure an upgrade first is told; resending the same request works. */
    static final String CONFIGURED_CONCURRENTLY = "Tracking configuration was saved concurrently. Please retry.";

    /**
     * The words {@code UpgradeService}'s ownership check uses for a missing upgrade, followed by its id, so
     * an upgrade deleted after that check reads the same as one it found missing.
     */
    static final String UPGRADE_NOT_FOUND = "Upgrade not found: ";

    private final TrackingConfigJpaRepository jpa;

    /**
     * {@inheritDoc}
     *
     * <p>Flushed, so a refusal is raised inside this call, where it can be translated, rather than at
     * commit, where it would be a 500. Two constraints are translated, by name, as
     * {@code UserRepositoryAdapter.saveAndFlush} does for the email:
     *
     * <ul>
     *   <li>the unique {@code upgrade_id}, refused when a concurrent first configuration won, into a
     *       retryable 409;
     *   <li>the foreign key to the upgrade, refused when another request deleted the upgrade after the
     *       caller's ownership check, into the 404 that check would now give.
     * </ul>
     *
     * <p>Any other violation means the code upstream let through what it should not have, and is rethrown
     * unchanged, so the client is not told to retry, or that the upgrade is gone, when neither would help
     * (ADR-074).
     */
    @Override
    public TrackingConfig save(TrackingConfig config) {
        try {
            return jpa.saveAndFlush(config);
        } catch (DataIntegrityViolationException violation) {
            if (violation.getCause() instanceof ConstraintViolationException constraint) {
                if (UPGRADE_UNIQUE_CONSTRAINT.equals(constraint.getConstraintName())) {
                    throw new RetryableConflictException(CONFIGURED_CONCURRENTLY);
                }
                if (UPGRADE_FOREIGN_KEY.equals(constraint.getConstraintName())) {
                    throw new ResourceNotFoundException(UPGRADE_NOT_FOUND + config.getUpgradeId());
                }
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
