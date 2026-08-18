package com.healthupgrades.upgrade.domain.service;
import com.healthupgrades.upgrade.domain.model.Difficulty;

import com.healthupgrades.common.domain.exception.BusinessRuleException; // thrown when the invariant is violated

/**
 * Stateless domain service enforcing the scheduling invariant that a user may not run more than a fixed
 * number of HARD upgrades at the same time.
 *
 * <p>Pure by design: it receives the user's current active-HARD count and decides, leaving the counting
 * query to the application layer. Framework-free — the application layer wires it as a Spring bean,
 * keeping the framework out of the domain.
 */
public class UpgradeSchedulingService {

    /** Maximum number of concurrently ACTIVE HARD upgrades a user may have. */
    private static final int MAX_HARD_ACTIVE = 3;

    /**
     * Validates that letting an upgrade run at the given difficulty would not exceed the HARD limit.
     *
     * <p>Applies to every route by which an upgrade can come to be ACTIVE and HARD — activating one that
     * is already HARD, and promoting an already-ACTIVE upgrade to HARD.
     *
     * @param difficulty      the difficulty the upgrade would run at
     * @param activeHardCount the user's current number of ACTIVE HARD upgrades, excluding this one
     */
    public void validateWithinHardLimit(Difficulty difficulty, long activeHardCount) {
        // Only HARD upgrades are capped; EASY/MEDIUM are always allowed.
        if (difficulty == Difficulty.HARD && activeHardCount >= MAX_HARD_ACTIVE) {
            throw new BusinessRuleException("Cannot run more than " + MAX_HARD_ACTIVE + " HARD upgrades simultaneously");
        }
    }
}
