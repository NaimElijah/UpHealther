/**
 * Health areas: the user-defined groupings ("Fitness", "Sleep") that upgrades are filed under (FR-6, FR-7).
 *
 * <ul>
 *   <li>{@code domain.model.HealthArea}: a plain entity with setters, because it has no state machine to
 *       guard; {@code domain.port.out.HealthAreaRepositoryPort} is its store.
 *   <li>{@code application}: {@code HealthAreaService} (create, read, update, delete) implements
 *       {@code HealthAreaQuery}, the port other contexts use.
 *   <li>{@code adapter.in.web}: {@code HealthAreaController} on {@code /api/health-areas}.
 *   <li>{@code adapter.out.persistence}: the package-private Spring Data repository and its adapter.
 * </ul>
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>It depends on no other context; the {@code upgrade} context calls {@code HealthAreaQuery.ownsArea} so
 *       an upgrade is filed only under an area its owner owns (BR-18).
 *   <li>Every by-id access is scoped to the owner, and another user's area reads exactly like a missing one
 *       (404, BR-15).
 *   <li>Deleting an area deletes no upgrade: the foreign key sets the upgrades' area to null (FR-8).
 *   <li>The name, icon and colour are bounded to their columns at the web boundary (BR-16).
 * </ul>
 */
package com.healthupgrades.healtharea;
