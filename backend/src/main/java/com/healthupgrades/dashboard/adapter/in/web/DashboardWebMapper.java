package com.healthupgrades.dashboard.adapter.in.web;

import com.healthupgrades.dashboard.application.port.in.DashboardView; // application aggregate being mapped
import com.healthupgrades.upgrade.adapter.in.web.UpgradeDto; // reused published presentation model
import com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper; // reused to map embedded upgrades
import com.healthupgrades.upgrade.domain.model.HealthUpgrade; // domain aggregate in each bucket
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Web-adapter mapper that turns a {@link DashboardView} (domain aggregate) into the {@link DashboardDto}
 * HTTP payload.
 *
 * <p>Reuses the upgrade context's {@link UpgradeWebMapper} to build the embedded {@link UpgradeDto}s — a
 * deliberate web-to-web reuse of upgrade's published presentation model. Every distinct upgrade across
 * the buckets is mapped once (single batched tracking-config query) and then indexed by id.
 */
@Component
@RequiredArgsConstructor
public class DashboardWebMapper {

    private final UpgradeWebMapper upgradeWebMapper; // reused to map embedded upgrades to DTOs

    /** Maps the dashboard aggregate into its HTTP response, batch-mapping upgrades once. */
    public DashboardDto toDto(DashboardView view) {
        // Collect every distinct upgrade referenced by any bucket, preserving one instance per id.
        Map<UUID, HealthUpgrade> distinctById = new LinkedHashMap<>();
        List.of(view.active(), view.planned(), view.today(), view.overdue(), view.recentlyCompleted())
                .forEach(bucket -> bucket.forEach(u -> distinctById.putIfAbsent(u.getId(), u)));

        // Map those upgrades to DTOs once (single batched tracking-config query), then index by id.
        Map<UUID, UpgradeDto> dtoById = upgradeWebMapper.toDtos(List.copyOf(distinctById.values())).stream()
                .collect(java.util.stream.Collectors.toMap(UpgradeDto::id, dto -> dto));

        return new DashboardDto(
                mapBucket(view.active(), dtoById),
                mapBucket(view.planned(), dtoById),
                mapBucket(view.today(), dtoById),
                mapBucket(view.overdue(), dtoById),
                view.weeklyCompletionRate(),
                view.streaks(),
                mapBucket(view.recentlyCompleted(), dtoById),
                view.areaSummaries().stream()
                        .map(a -> new DashboardDto.AreaSummary(a.areaId(), a.areaName(),
                                a.totalUpgrades(), a.activeCount(), a.completedCount()))
                        .toList());
    }

    /** Resolves a bucket of domain upgrades to their already-mapped DTOs, preserving order. */
    private List<UpgradeDto> mapBucket(List<HealthUpgrade> bucket, Map<UUID, UpgradeDto> dtoById) {
        return bucket.stream().map(u -> dtoById.get(u.getId())).toList();
    }
}
