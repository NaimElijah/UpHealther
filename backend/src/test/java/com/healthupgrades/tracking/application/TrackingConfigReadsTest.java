package com.healthupgrades.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.healthupgrades.support.ATrackingConfig;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The unscoped read of tracking configurations by upgrade id, moved off {@code TrackingService} with its
 * test (S5.B6).
 */
class TrackingConfigReadsTest {

    private final TrackingConfigRepositoryPort configRepository = mock(TrackingConfigRepositoryPort.class);
    private final TrackingConfigReads reads = new TrackingConfigReads(configRepository);

    @Test
    void GivenSeveralUpgrades_WhenTheirConfigurationsAreResolved_ThenTheyAreFetchedInOneBatch() {
        // NFR-14. The upgrade list endpoint calls this once for every row it is about to render; a
        // per-id lookup here is the N+1 that made the list slow.
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<TrackingConfig> stored = List.of(
                ATrackingConfig.forUpgrade(ids.get(0)).build(),
                ATrackingConfig.forUpgrade(ids.get(2)).build());
        when(configRepository.findByUpgradeIdIn(ids)).thenReturn(stored);

        List<TrackingConfig> read = reads.findByUpgradeIds(ids);

        assertThat(read).as("what the one batched query returned, unchanged").isEqualTo(stored);
        verify(configRepository).findByUpgradeIdIn(ids);
        verify(configRepository, never()).findByUpgradeId(any());
    }
}
