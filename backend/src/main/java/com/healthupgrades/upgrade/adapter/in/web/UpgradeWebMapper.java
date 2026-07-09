package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.tracking.application.port.in.TrackingConfigQuery; // inbound port for another context's config
import com.healthupgrades.tracking.adapter.in.web.TrackingConfigDto; // embedded web DTO (published presentation model)
import com.healthupgrades.tracking.domain.model.TrackingConfig; // domain config obtained via the port
import com.healthupgrades.upgrade.domain.model.HealthUpgrade; // domain aggregate being mapped
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Web-adapter mapper that turns {@link HealthUpgrade} domain objects into {@link UpgradeDto} responses,
 * enriching each with its tracking config.
 *
 * <p>Living in the web layer (not the application service) is what keeps the upgrade application free of
 * any tracking dependency: the config is fetched here through tracking's inbound {@link TrackingConfigQuery}
 * port, and the embedded {@link TrackingConfigDto} is assembled here.
 */
@Component
@RequiredArgsConstructor
public class UpgradeWebMapper {

    private final TrackingConfigQuery trackingConfigQuery; // cross-context read of tracking configs

    /** Maps a single upgrade, looking up its tracking config on demand. */
    public UpgradeDto toDto(HealthUpgrade upgrade) {
        // Fetch this upgrade's config (if any) and fold it into the response.
        TrackingConfigDto config = trackingConfigQuery.findByUpgradeId(upgrade.getId())
                .map(UpgradeWebMapper::toTrackingConfigDto)
                .orElse(null);
        return toDto(upgrade, config);
    }

    /**
     * Batch variant of {@link #toDto(HealthUpgrade)}. Loads every upgrade's tracking config in a single
     * query instead of one lookup per upgrade, avoiding the N+1 pattern on list/dashboard endpoints.
     */
    public List<UpgradeDto> toDtos(List<HealthUpgrade> upgrades) {
        if (upgrades.isEmpty()) return List.of(); // nothing to map
        List<UUID> ids = upgrades.stream().map(HealthUpgrade::getId).toList(); // all upgrade ids
        // One batched query keyed by upgrade id.
        Map<UUID, TrackingConfigDto> configsByUpgradeId = trackingConfigQuery.findByUpgradeIds(ids).stream()
                .collect(Collectors.toMap(TrackingConfig::getUpgradeId, UpgradeWebMapper::toTrackingConfigDto));
        return upgrades.stream().map(u -> toDto(u, configsByUpgradeId.get(u.getId()))).toList();
    }

    /** Assembles the response record from an upgrade and its (possibly null) tracking config. */
    private UpgradeDto toDto(HealthUpgrade u, TrackingConfigDto trackingConfig) {
        return new UpgradeDto(u.getId(), u.getUserId(), u.getAreaId(), u.getTitle(),
                u.getDescription(), u.getType(), u.getStatus(), u.getDifficulty(),
                u.getPlannedStartDate(), u.getActualStartDate(), u.getTargetEndDate(),
                u.getMotivation(), u.getSuccessCriteria(), u.isOverdue(), u.getVersion(),
                trackingConfig, u.getCreatedAt(), u.getUpdatedAt());
    }

    /** Maps a tracking-config domain object to its embedded web DTO. */
    private static TrackingConfigDto toTrackingConfigDto(TrackingConfig c) {
        return new TrackingConfigDto(c.getId(), c.getUpgradeId(), c.getTrackingType(),
                c.getFrequency(), c.getTargetNumericValue(), c.getTargetUnit(), c.getRequiredDaily());
    }
}
