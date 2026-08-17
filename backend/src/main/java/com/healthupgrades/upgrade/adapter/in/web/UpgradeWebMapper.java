package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.upgrade.application.port.in.UpgradeDetails; // the shape use cases accept
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummary; // what this context asked for
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort; // who fills it in
import com.healthupgrades.upgrade.domain.model.HealthUpgrade; // domain aggregate being mapped
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Web-adapter mapper that turns {@link HealthUpgrade} domain objects into {@link UpgradeDto} responses,
 * enriching each with its tracking configuration.
 *
 * <p>The configuration arrives through {@link UpgradeTrackingSummaryPort}, a contract this context owns
 * and another satisfies, so composing the response costs this context no dependency on whichever
 * context supplies the data.
 */
@Component
@RequiredArgsConstructor
public class UpgradeWebMapper {

    private final UpgradeTrackingSummaryPort trackingSummaries; // outbound port: tracking setup per upgrade
    private final Clock clock; // decides the "as of" date for the derived overdue flag

    /** Request record to the use-case input shape. */
    public UpgradeDetails toDetails(UpgradeRequest req) {
        return new UpgradeDetails(req.areaId(), req.title(), req.description(), req.type(),
                req.difficulty(), req.plannedStartDate(), req.targetEndDate(),
                req.motivation(), req.successCriteria());
    }

    /** Maps a single upgrade, looking up its tracking configuration on demand. */
    public UpgradeDto toDto(HealthUpgrade upgrade) {
        UpgradeTrackingSummary summary = trackingSummaries
                .findByUpgradeIds(List.of(upgrade.getId()))
                .get(upgrade.getId());
        return toDto(upgrade, toTrackingConfigDto(summary));
    }

    /**
     * Batch variant of {@link #toDto(HealthUpgrade)}. Resolves every upgrade's tracking configuration in
     * one call instead of one lookup per upgrade, avoiding the N+1 pattern on list endpoints.
     */
    public List<UpgradeDto> toDtos(List<HealthUpgrade> upgrades) {
        if (upgrades.isEmpty()) return List.of(); // nothing to map
        List<UUID> ids = upgrades.stream().map(HealthUpgrade::getId).toList();
        Map<UUID, UpgradeTrackingSummary> summaries = trackingSummaries.findByUpgradeIds(ids);
        return upgrades.stream()
                .map(u -> toDto(u, toTrackingConfigDto(summaries.get(u.getId()))))
                .toList();
    }

    /** Assembles the response record from an upgrade and its (possibly null) tracking configuration. */
    private UpgradeDto toDto(HealthUpgrade u, UpgradeTrackingConfigDto trackingConfig) {
        return new UpgradeDto(u.getId(), u.getUserId(), u.getAreaId(), u.getTitle(),
                u.getDescription(), u.getType(), u.getStatus(), u.getDifficulty(),
                u.getPlannedStartDate(), u.getActualStartDate(), u.getTargetEndDate(),
                u.getMotivation(), u.getSuccessCriteria(), u.isOverdue(LocalDate.now(clock)), u.getVersion(),
                trackingConfig, u.getCreatedAt(), u.getUpdatedAt());
    }

    /** An upgrade with no tracking configuration reports none, rather than an empty object. */
    private static UpgradeTrackingConfigDto toTrackingConfigDto(UpgradeTrackingSummary s) {
        if (s == null) return null;
        return new UpgradeTrackingConfigDto(s.id(), s.upgradeId(), s.trackingType(),
                s.frequency(), s.targetNumericValue(), s.targetUnit(), s.requiredDaily());
    }
}
