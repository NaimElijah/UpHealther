package com.healthupgrades.upgrade.domain.service;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The max-concurrent-HARD rule: the most-cited business invariant in the repository, and previously the
 * only domain service with no test of its own.
 */
class UpgradeSchedulingServiceTest {

    /** Mirrors the limit the service enforces; kept here so a change to it fails these tests loudly. */
    private static final int LIMIT = 3;

    private final UpgradeSchedulingService service = new UpgradeSchedulingService();

    @Test
    void hardUpgrade_belowTheLimit_isAllowed() {
        assertThatCode(() -> service.validateWithinHardLimit(Difficulty.HARD, LIMIT - 1))
                .doesNotThrowAnyException();
    }

    @Test
    void hardUpgrade_atTheLimit_isRejected() {
        // At the limit the slots are already taken, so this one would be the (LIMIT + 1)th.
        assertThatThrownBy(() -> service.validateWithinHardLimit(Difficulty.HARD, LIMIT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining(String.valueOf(LIMIT));
    }

    @Test
    void hardUpgrade_aboveTheLimit_isRejected() {
        assertThatThrownBy(() -> service.validateWithinHardLimit(Difficulty.HARD, LIMIT + 5))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void hardUpgrade_withNoneRunning_isAllowed() {
        assertThatCode(() -> service.validateWithinHardLimit(Difficulty.HARD, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void easyUpgrade_isUncappedEvenWellPastTheHardLimit() {
        assertThatCode(() -> service.validateWithinHardLimit(Difficulty.EASY, LIMIT + 100))
                .doesNotThrowAnyException();
    }

    @Test
    void mediumUpgrade_isUncappedEvenWellPastTheHardLimit() {
        assertThatCode(() -> service.validateWithinHardLimit(Difficulty.MEDIUM, LIMIT + 100))
                .doesNotThrowAnyException();
    }

    @Test
    void unsetDifficulty_isUncapped() {
        // Difficulty is optional on an upgrade, so an unset one must not be treated as HARD.
        assertThatCode(() -> service.validateWithinHardLimit(null, LIMIT + 100))
                .doesNotThrowAnyException();
    }
}
