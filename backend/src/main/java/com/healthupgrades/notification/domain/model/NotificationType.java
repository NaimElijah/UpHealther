package com.healthupgrades.notification.domain.model;

/**
 * What a notification is about.
 *
 * <p>The frontend switches on this to choose an icon and where clicking the notification leads, so a new
 * value needs a matching entry there — an unmapped type renders with a fallback rather than failing.
 *
 * <p>Most values mirror a domain event; the last two are raised by the scheduler instead, having no
 * event behind them.
 */
public enum NotificationType {

    /** An upgrade was added to the backlog. */
    UPGRADE_CREATED,

    /** An upgrade was committed to a start date. */
    UPGRADE_PLANNED,

    /** An upgrade started or resumed. */
    UPGRADE_ACTIVATED,

    /** An upgrade was suspended. */
    UPGRADE_PAUSED,

    /** An upgrade was finished. */
    UPGRADE_COMPLETED,

    /** An upgrade was given up on. */
    UPGRADE_ABANDONED,

    /** A streak reached a milestone length. */
    STREAK_ACHIEVED,

    /** A running upgrade passed its target end date. Raised at most once per upgrade. */
    UPGRADE_OVERDUE,

    /** A reflection was written. */
    REFLECTION_ADDED,

    /** A reminder the user configured came due. Scheduler-raised. */
    REMINDER,

    /** The daily nudge for a user with running upgrades who has logged nothing today. Scheduler-raised,
     *  at most once a day. */
    CHECKIN_REMINDER
}
