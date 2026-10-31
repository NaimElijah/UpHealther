/**
 * The notification inbox and its real-time push: notifications raised by domain events and by scheduled sweeps,
 * a REST inbox to read and acknowledge them, a STOMP push to connected clients, and the nightly deletion of old
 * ones.
 *
 * <p>Nothing creates a notification through the API. {@code NotificationEventListener} turns upgrade, streak,
 * reflection and overdue events into notifications, and {@code NotificationSweepService} produces the daily
 * check-in nudge and the due reminders on the crons of {@code NotificationScheduler}. The inbox
 * ({@code NotificationController}) lists the newest fifty, counts the unread, and marks one or all as read. The
 * same payload is pushed to {@code /user/queue/notifications}, and a client that was offline finds it in the
 * inbox (FR-32).
 *
 * <p>Rules a maintainer must keep:
 *
 * <ul>
 *   <li><strong>Raised only after the write commits, and stored in a transaction of its own.</strong> A
 *       listener on {@code AFTER_COMMIT} that writes must be {@code REQUIRES_NEW}, or its write joins a
 *       transaction that will never commit again and is silently lost (#169, ADR-028). A new listener also
 *       goes into {@code NotificationCommitIT}'s parameters, which fails until it does. The push itself waits
 *       for that commit.
 *   <li><strong>{@code StompNotificationPushAdapter} logs a failed push instead of throwing.</strong> The
 *       stored row is what FR-32 relies on, and an exception escaping an after-commit callback would fail its
 *       caller after the commit.
 *   <li><strong>A sweep carries on past a notification that fails to save</strong> and still ends as a failed
 *       run; it stops only when the database is out of reach (NFR-50, ADR-023).
 *   <li><strong>An overdue upgrade is announced once per target date it misses</strong> (BR-11, ADR-025).
 *   <li><strong>One mapper builds the payload for both transports</strong> ({@code NotificationWebMapper}), so
 *       the REST list and the push cannot differ. The STOMP adapter importing it is the one reverse arrow
 *       between adapters that {@code HexagonalArchitectureTest} leaves open.
 *   <li><strong>Reads of other contexts' rows go through their sweep ports</strong>
 *       ({@code UpgradeSweepQuery}, {@code ProgressSweepQuery}, {@code ReminderSweepQuery}), which only this
 *       context's sweep service may use.
 *   <li><strong>Only the nightly job deletes across accounts.</strong> {@code NotificationRetentionService}
 *       deletes notifications older than ninety days in bounded batches, and only
 *       {@code NotificationRetentionScheduler} may use it (NFR-60, ADR-075).
 * </ul>
 *
 * <p>Configuration: the crons under {@code app.notifications.schedules}, read in UTC.
 */
package com.healthupgrades.notification;
