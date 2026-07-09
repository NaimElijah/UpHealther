package com.healthupgrades.upgrade.domain;

import com.healthupgrades.common.exception.BusinessRuleException; // thrown when the invariant is violated

/**
 * Stateless domain service enforcing the scheduling invariant that a user may not run more than a fixed
 * number of HARD upgrades at the same time.
 *
 * <p>Pure by design: it receives the user's current active-HARD count and decides, leaving the counting
 * query to the application layer. Framework-free — the application provides it as a bean (see
 * {@code UpgradeBeansConfig}) rather than it being a Spring component.
 */
public class UpgradeSchedulingService {

    /** Maximum number of concurrently ACTIVE HARD upgrades a user may have. */
    private static final int MAX_HARD_ACTIVE = 3;

    /**
     * Validates that activating an upgrade of the given difficulty would not exceed the HARD limit.
     *
     * @param difficulty      the difficulty of the upgrade being activated
     * @param activeHardCount the user's current number of ACTIVE HARD upgrades
     */
    public void validateCanActivate(Difficulty difficulty, long activeHardCount) {
        // Only HARD upgrades are capped; EASY/MEDIUM activations are always allowed.
        if (difficulty == Difficulty.HARD && activeHardCount >= MAX_HARD_ACTIVE) {
            throw new BusinessRuleException("Cannot activate more than " + MAX_HARD_ACTIVE + " HARD upgrades simultaneously");
        }
    }
}
