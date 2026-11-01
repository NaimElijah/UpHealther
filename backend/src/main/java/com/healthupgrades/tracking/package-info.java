/**
 * Tracking: how an upgrade's progress is measured, the per-day progress entries logged against it, and the
 * streaks computed from them (FR-16 to FR-22).
 *
 * <ul>
 *   <li>{@code domain.model}: {@code TrackingConfig} (at most one per upgrade), {@code ProgressEntry} (at
 *       most one per upgrade per date, BR-6), and the {@code TrackingType} and {@code Frequency} enums.
 *   <li>{@code domain.service}: {@code ProgressEvaluationService} (what counts as a success, BR-7, BR-21) and
 *       {@code StreakCalculator} (current and longest streak, and the milestone an entry reaches, BR-9,
 *       BR-10); both are framework-free and wired by {@code application.TrackingBeansConfig}.
 *   <li>{@code application}: {@code TrackingService}, plus the package-private {@code ProgressSweeps} and
 *       {@code TrackingConfigReads}, each behind a port of its own.
 *   <li>{@code adapter.in.web}: {@code ProgressController} and {@code TrackingConfigController};
 *       {@code adapter.in.composition}: {@code UpgradeTrackingSummaryAdapter}.
 * </ul>
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>It depends on {@code upgrade} for ownership ({@code UpgradeQuery}); {@code upgrade} must not depend
 *       back. So {@code upgrade} declares {@code UpgradeTrackingSummaryPort} and this context implements it
 *       (ADR-002, ADR-077).
 *   <li>Where an upgrade has a configuration, the server decides {@code completed}; without one the entry
 *       keeps what the caller sent (BR-7). Changing a configuration does not rescore past entries.
 *   <li>A milestone ({@code StreakAchieved}) is announced once per multiple of seven a streak reaches or jumps
 *       past, naming the highest one crossed (BR-10).
 *   <li>A progress date may be at most the server's tomorrow ({@code NoLaterThanTomorrow}, FR-18, ADR-020).
 *   <li>{@code TrackingConfigQuery} is not scoped by owner; ArchUnit restricts it and the hops after it
 *       (BR-15). Do not add a caller or pass it a request-supplied id.
 *   <li>Known deviation: a duplicate entry that races past the existence check is answered 500, not 409
 *       (BR-6, #146).
 * </ul>
 */
package com.healthupgrades.tracking;
