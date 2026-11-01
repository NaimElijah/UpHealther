/**
 * The dashboard: a read model that composes one user's upgrades, progress, streaks and health areas into the
 * figures the home page draws (FR-27).
 *
 * <p>Two layers only, by design: it has no domain model, no outbound port and no persistence, and stores
 * nothing. Every request builds the dashboard afresh.
 *
 * <ul>
 *   <li>{@code adapter.in.web}: {@code DashboardController} ({@code GET /api/dashboard}), the
 *       {@code DashboardDto} response and {@code DashboardWebMapper}.
 *   <li>{@code application}: {@code DashboardAggregationService}, which implements {@code DashboardQuery}
 *       and returns a {@code DashboardView} of domain objects; no DTO reaches it.
 * </ul>
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>It reads other contexts only through their inbound ports ({@code UpgradeQuery},
 *       {@code ProgressQuery}, {@code StreakQuery}, {@code HealthAreaQuery}).
 *   <li>The one adapter-level reuse is {@code DashboardWebMapper} mapping embedded upgrades through
 *       {@code UpgradeWebMapper}; ArchUnit lets only {@code UpgradeController} and {@code DashboardWebMapper}
 *       use that mapper, and only {@code DashboardController} use this one.
 *   <li>"Today" is the injected clock's day, in UTC (ADR-020); the weekly rate and "recently completed" are
 *       defined on {@code DashboardDto} and {@code DashboardView}.
 * </ul>
 */
package com.healthupgrades.dashboard;
