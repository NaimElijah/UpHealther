/**
 * Reflections: the periodic written reviews a user makes about how an upgrade is going (FR-23, FR-24).
 *
 * <p>A reflection is append-only (BR-13): there is no edit or delete path, and it goes only with its upgrade
 * (FR-14). Several may exist for one upgrade and one date.
 *
 * <ul>
 *   <li>{@code domain.model.Reflection}, {@code domain.event.ReflectionAdded}, and the repository port.
 *   <li>{@code application.ReflectionService}: writes and reads an upgrade's reflections.
 *   <li>{@code adapter.in.web}: {@code ReflectionController} on
 *       {@code /api/upgrades/{upgradeId}/reflections}.
 * </ul>
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Ownership is the upgrade's: {@code ReflectionService} confirms the upgrade through
 *       {@code UpgradeQuery.getOwnedUpgrade}, so a foreign or missing upgrade is a 404 (BR-15).
 *   <li>A creation is audited against the upgrade, not the reflection (ADR-065, section 11), and publishes
 *       {@code ReflectionAdded}, which carries ids only, never the text.
 *   <li>Ratings are one to five (BR-19); a null date means today by the injected clock.
 * </ul>
 */
package com.healthupgrades.reflection;
