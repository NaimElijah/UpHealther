/**
 * Reminders: recurring nudges attached to an upgrade, each with a time of day and an optional day-of-week
 * filter (FR-25, FR-26).
 *
 * <ul>
 *   <li>{@code domain.model.Reminder} answers {@code isDueAt}; {@code ReminderDays} is the only code that
 *       parses or formats the day filter.
 *   <li>{@code application.ReminderService}: create, list, reschedule and delete. {@code ReminderSweeps}
 *       (package-private) serves the dispatch job through {@code ReminderSweepQuery}.
 *   <li>{@code adapter.in.web}: {@code ReminderController}, with create and list under
 *       {@code /api/upgrades/{upgradeId}/reminders} and update and delete under {@code /api/reminders/{id}}.
 *   <li>{@code adapter.out.persistence}: the Spring Data repository and its minute query.
 * </ul>
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>A reminder has no owner column; ownership is its upgrade's, checked through
 *       {@code UpgradeQuery.getOwnedUpgrade}. Another user's reminder reads exactly like an unknown id
 *       (BR-15).
 *   <li>An empty or absent day filter means every day. An unrecognised day name is rejected (422), because
 *       dropping it would turn a twice-weekly reminder into a daily one.
 *   <li>The time of day is compared with the server's clock, which is UTC (ADR-020); a reminder has no zone
 *       of its own.
 *   <li>The dispatch job runs every minute by default and reads every user's enabled reminders for that
 *       minute through {@code ReminderSweepQuery}, which only the scheduled jobs' application services may
 *       use (BR-15).
 * </ul>
 */
package com.healthupgrades.reminder;
