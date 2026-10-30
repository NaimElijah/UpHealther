package com.healthupgrades.upgrade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers the sweeps' reads of every user's upgrades, answered straight from the repository. */
@ExtendWith(MockitoExtension.class)
class UpgradeSweepsTest {

    @Mock
    UpgradeRepositoryPort repository;

    @InjectMocks
    UpgradeSweeps sweeps;

    @Test
    void GivenUpgradesOfSeveralUsers_WhenTheSweepAsksForAStatus_ThenEveryUsersUpgradesInItComeBack() {
        List<HealthUpgrade> active = List.of(AnUpgrade.active(UUID.randomUUID()), AnUpgrade.active(UUID.randomUUID()));
        when(repository.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(active);

        assertThat(sweeps.findByStatus(UpgradeStatus.ACTIVE)).isEqualTo(active);
    }

    @Test
    void GivenIdsOfSeveralUsersUpgrades_WhenTheSweepLoadsThem_ThenTheyComeBackInOneBatch() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<HealthUpgrade> found = List.of(AnUpgrade.active(UUID.randomUUID()));
        when(repository.findAllById(ids)).thenReturn(found);

        assertThat(sweeps.findAllById(ids)).isEqualTo(found);
    }
}
