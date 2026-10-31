/**
 * Health upgrades: the core aggregate, a planned health improvement that moves through a guarded lifecycle
 * (FR-9 to FR-15).
 *
 * <p>{@code HealthUpgrade} owns its state machine ({@code IDEA -> PLANNED -> ACTIVE <-> PAUSED},
 * {@code ACTIVE -> COMPLETED}, any state but COMPLETED and ABANDONED to {@code ABANDONED}, and a reschedule
 * that revives an abandoned one to {@code PLANNED}). Status has no setter; each transition throws
 * {@code BusinessRuleException} on an illegal move. {@code UpgradeService} follows load, call the domain
 * method, save, publish the event, return the aggregate.
 *
 * <ul>
 *   <li>{@code domain}: the aggregate, its enums, the {@code HealthUpgrade*} lifecycle events and
 *       {@code UpgradeOverdueDetected}, {@code UpgradeRepositoryPort}, and {@code UpgradeSchedulingService}
 *       (the HARD limit).
 *   <li>{@code application}: {@code UpgradeService}, {@code UpgradeOverdueService}, and the package-private
 *       {@code UpgradeSweeps}; inbound ports in {@code port.in}, and the tracking-summary port in
 *       {@code port.out}.
 *   <li>{@code adapter.in.web} ({@code /api/upgrades}), {@code adapter.in.scheduling} (the overdue scan,
 *       08:00 UTC by default) and {@code adapter.out.persistence}.
 * </ul>
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>At most three upgrades may be ACTIVE at HARD at once (BR-5). The check runs on every route into a
 *       running HARD upgrade, and its count takes a per-user advisory try-lock, so a concurrent check is
 *       refused with a retryable 409 (ADR-074).
 *   <li>Every user-facing read takes the owner's id; another user's upgrade is a 404 (BR-15). Reads across
 *       every user are on {@code UpgradeSweepQuery}, for the scheduled jobs only.
 *   <li>An upgrade is filed only under an area its owner owns, via {@code HealthAreaQuery} (BR-18), the one
 *       other context it depends on. It does not depend on {@code tracking}: it declares
 *       {@code UpgradeTrackingSummaryPort}, and mirrors tracking's enums as {@code UpgradeTrackingType} and
 *       {@code UpgradeTrackingFrequency} (ADR-002, ADR-077).
 *   <li>{@code PROTOCOL} is a retired type: stored rows keep it, but it is refused on creation and on a
 *       change to it (FR-9).
 *   <li>The aggregate is {@code @Version}ed (BR-14), but no request carries the version back yet (#194).
 *   <li>Request and response records are the frontend's types: run {@code npm run generate:wire-types} after
 *       changing one.
 * </ul>
 */
package com.healthupgrades.upgrade;
