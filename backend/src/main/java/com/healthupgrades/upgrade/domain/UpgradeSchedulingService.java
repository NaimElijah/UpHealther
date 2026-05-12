package com.healthupgrades.upgrade.domain;

import com.healthupgrades.common.exception.BusinessRuleException;
import com.healthupgrades.upgrade.infrastructure.UpgradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UpgradeSchedulingService {

    private static final int MAX_HARD_ACTIVE = 3;

    private final UpgradeRepository upgradeRepository;

    public void validateCanActivate(UUID userId, Difficulty difficulty) {
        if (difficulty == Difficulty.HARD) {
            long count = upgradeRepository.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD);
            if (count >= MAX_HARD_ACTIVE) {
                throw new BusinessRuleException("Cannot activate more than " + MAX_HARD_ACTIVE + " HARD upgrades simultaneously");
            }
        }
    }
}
