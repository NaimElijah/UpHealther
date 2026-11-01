package com.healthupgrades.upgrade.application.port.in;

import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbound (driving) port exposing read access to upgrades as DOMAIN objects.
 *
 * <p>This is how other bounded contexts (tracking, reflection, reminder, dashboard, notification) obtain
 * upgrades without reaching into the upgrade context's persistence — they depend on this interface and
 * receive {@link HealthUpgrade} domain objects, never a web DTO or a repository.
 *
 * <p>Every read is scoped by the owner, so another user's upgrade is reported as absent (BR-15). The
 * scheduled sweeps' reads across every user are on {@link UpgradeSweepQuery} instead.
 */
public interface UpgradeQuery {

    /** Returns the user-owned upgrade, throwing {@code ResourceNotFoundException} if missing or foreign. */
    HealthUpgrade getOwnedUpgrade(UUID userId, UUID id);

    /** Returns the user-owned upgrade if it exists, without throwing (for best-effort lookups). */
    Optional<HealthUpgrade> findOwned(UUID userId, UUID id);

    /** All of a user's upgrades. */
    List<HealthUpgrade> findByUser(UUID userId);
}
