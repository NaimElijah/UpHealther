package com.healthupgrades.tracking.adapter.in.composition;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.ATrackingConfig;
import com.healthupgrades.tracking.application.port.in.TrackingConfigQuery;
import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.model.TrackingType;
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummary;
import com.healthupgrades.upgrade.domain.model.UpgradeTrackingFrequency;
import com.healthupgrades.upgrade.domain.model.UpgradeTrackingType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The supplying half of FR-15: what the upgrade response says about an upgrade's tracking setup is
 * what this adapter translates out of the tracking context.
 *
 * <p>{@code UpgradeControllerTest} stubs the port this adapter implements, so a field this translation
 * puts into the wrong slot is visible only here.
 */
class UpgradeTrackingSummaryAdapterTest {

    private final UUID configured = UUID.randomUUID();

    /** Every field differs from every other, so a swap between any two of them changes the summary. */
    private final TrackingConfig config = ATrackingConfig.forUpgrade(configured)
            .trackingType(TrackingType.NUMERIC)
            .frequency(Frequency.WEEKLY)
            .targetNumericValue(10_000.0)
            .targetUnit("steps")
            .requiredDaily(true)
            .build();

    /** A query that knows one configuration, and answers with it only when it is asked about. */
    private final TrackingConfigQuery query = ids -> ids.contains(configured) ? List.of(config) : List.of();

    private final UpgradeTrackingSummaryAdapter adapter = new UpgradeTrackingSummaryAdapter(query);

    @Test
    void GivenAConfiguredUpgrade_WhenItsSummaryIsFound_ThenEveryFieldCrossesUnderItsOwnName() {
        Map<UUID, UpgradeTrackingSummary> summaries = adapter.findByUpgradeIds(List.of(configured));

        assertThat(summaries)
                .containsExactly(Map.entry(
                        configured,
                        new UpgradeTrackingSummary(
                                config.getId(),
                                configured,
                                UpgradeTrackingType.NUMERIC,
                                UpgradeTrackingFrequency.WEEKLY,
                                10_000.0,
                                "steps",
                                true)));
    }
}
