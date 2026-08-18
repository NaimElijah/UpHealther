package com.healthupgrades.notification.domain.model;

/**
 * How a notification should read, which drives the icon and colour the frontend gives it.
 *
 * <p>Orthogonal to {@code NotificationType}: the type says what happened, the category says how to
 * present it. Several types share a category.
 */
public enum NotificationCategory {

    /** Neutral: something was recorded. */
    INFO,

    /** Positive: something went well and is worth celebrating. */
    SUCCESS,

    /** Needs attention: something has slipped. */
    WARNING,

    /** A nudge to act now. */
    REMINDER
}
