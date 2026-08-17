package com.healthupgrades.tracking.adapter.in.composition;

import com.healthupgrades.tracking.application.port.in.TrackingConfigQuery;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummary;
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Driving adapter that answers the upgrade context's {@link UpgradeTrackingSummaryPort} from this
 * context's own {@link TrackingConfigQuery}.
 *
 * <p>This is the supplying half of an inverted dependency. The upgrade response has always shown an
 * upgrade's tracking setup; having the upgrade context fetch it directly created a cycle, because this
 * context already depends on {@code upgrade} for ownership checks. Declaring the contract there and
 * implementing it here leaves a single arrow, from this context to that one.
 *
 * <p>Translating {@link TrackingConfig} into the upgrade context's own record is the point, not
 * incidental: it is what stops this context's domain types becoming part of that context's API.
 */
@Component
@RequiredArgsConstructor
public class UpgradeTrackingSummaryAdapter implements UpgradeTrackingSummaryPort {

    private final TrackingConfigQuery trackingConfigQuery; // this context's own inbound read port

    /** {@inheritDoc} */
    @Override
    public Map<UUID, UpgradeTrackingSummary> findByUpgradeIds(Collection<UUID> upgradeIds) {
        if (upgradeIds.isEmpty()) return Map.of(); // nothing asked for, nothing queried
        return trackingConfigQuery.findByUpgradeIds(upgradeIds).stream()
                .collect(Collectors.toMap(TrackingConfig::getUpgradeId, this::toSummary));
    }

    /** Enum values cross as their names, keeping this context's types out of the upgrade contract. */
    private UpgradeTrackingSummary toSummary(TrackingConfig config) {
        return new UpgradeTrackingSummary(
                config.getId(),
                config.getUpgradeId(),
                config.getTrackingType() != null ? config.getTrackingType().name() : null,
                config.getFrequency() != null ? config.getFrequency().name() : null,
                config.getTargetNumericValue(),
                config.getTargetUnit(),
                config.getRequiredDaily());
    }
}
