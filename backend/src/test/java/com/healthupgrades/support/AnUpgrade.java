package com.healthupgrades.support;

import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;

import java.util.UUID;

/**
 * Upgrades for tests, in whichever lifecycle state the test is about.
 *
 * <p>The aggregate has no setters and {@code HealthUpgrade.create} always returns an {@code IDEA}, so a
 * test that needs an upgrade already {@code ACTIVE} either walks it through every transition or reaches
 * for the Lombok builder. Walking the transitions makes the test depend on rules it is not testing — a
 * tracking test would start failing when the lifecycle changes — so the builder is right here, and this
 * class is where the reason for using it lives.
 *
 * <p>Production code must still use {@code HealthUpgrade.create}: the builder skips the invariants
 * (BR-4), which is exactly why {@code HealthUpgradeTest} exercises {@code create} directly rather than
 * going through this.
 */
public final class AnUpgrade {

    public static final String TITLE = "Cold showers";

    private AnUpgrade() {
    }

    /** An upgrade owned by the given user, with the required fields filled in and a fresh id. */
    public static HealthUpgrade.HealthUpgradeBuilder ownedBy(UUID userId) {
        return HealthUpgrade.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .title(TITLE)
                .type(UpgradeType.HABIT);
    }

    /** An upgrade owned by the given user, in the given state. */
    public static HealthUpgrade inState(UUID userId, UpgradeStatus status) {
        return ownedBy(userId).status(status).build();
    }

    public static HealthUpgrade idea(UUID userId) {
        return inState(userId, UpgradeStatus.IDEA);
    }

    public static HealthUpgrade planned(UUID userId) {
        return inState(userId, UpgradeStatus.PLANNED);
    }

    public static HealthUpgrade active(UUID userId) {
        return inState(userId, UpgradeStatus.ACTIVE);
    }

    public static HealthUpgrade paused(UUID userId) {
        return inState(userId, UpgradeStatus.PAUSED);
    }

    public static HealthUpgrade completed(UUID userId) {
        return inState(userId, UpgradeStatus.COMPLETED);
    }

    public static HealthUpgrade abandoned(UUID userId) {
        return inState(userId, UpgradeStatus.ABANDONED);
    }
}
